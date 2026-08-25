package app.castmirror.core

import app.castmirror.core.cast.channel.CastChannel
import app.castmirror.core.cast.channel.CastChannelListener
import app.castmirror.core.cast.channel.ConnectParams
import app.castmirror.core.cast.channel.Namespaces
import app.castmirror.core.cast.channel.ReceiverMessageTypes
import app.castmirror.core.cast.channel.ReceiverStatus
import app.castmirror.core.cast.channel.TlsCastSocket
import app.castmirror.core.cast.streaming.DatagramUdpTransport
import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.EnqueueResult
import app.castmirror.core.cast.streaming.FrameSource
import app.castmirror.core.cast.streaming.Sender
import app.castmirror.core.cast.streaming.SenderPacketRouter
import app.castmirror.core.cast.streaming.UdpTransport
import app.castmirror.core.cast.streaming.messages.Answer
import app.castmirror.core.cast.streaming.messages.AnswerResult
import app.castmirror.core.cast.streaming.messages.AudioOfferStream
import app.castmirror.core.cast.streaming.messages.Offer
import app.castmirror.core.cast.streaming.messages.OfferBuilder
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.messages.OfferStream
import app.castmirror.core.cast.streaming.messages.ReceiverCapabilities
import app.castmirror.core.cast.streaming.messages.Resolution
import app.castmirror.core.cast.streaming.messages.SessionMessenger
import app.castmirror.core.cast.streaming.messages.VideoOfferStream
import app.castmirror.core.cast.streaming.messages.StreamType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** The states a mirroring session moves through. */
enum class SessionState {
    /** Nothing has been started yet. */
    IDLE,

    /** TCP/TLS connect and the platform virtual connection are being established. */
    CONNECTING,

    /** The control channel is up; app availability is being probed. */
    CONNECTED,

    /** A `LAUNCH` is outstanding. */
    LAUNCHING,

    /** The app is running and OFFER/ANSWER is in progress. */
    NEGOTIATING,

    /** RTP is flowing. */
    STREAMING,

    /** Teardown is in progress. */
    STOPPING,

    /** The session ended cleanly. */
    TERMINATED,

    /** The session ended with an error; see [SessionController.failure]. */
    FAILED,
}

/** Everything that went wrong, with the state it went wrong in. */
class SessionException(
    /** The state the session was in. */
    val state: SessionState,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Decides whether to retry a session with the Android-TV RTP payload-type quirk values.
 *
 * SPEC-UNVERIFIED: which receiver firmwares still require audio PT 127 / video PT 96 is unknown —
 * openscreen's own TODO (issuetracker.google.com/184438154) says "we need to figure out what
 * receivers need this still, if any" (spec §3.3, §10.2, §11 item 3). The spec's recommended
 * heuristic, implemented by [DefaultPayloadTypeFallbackPolicy], is: if the ANSWER was fine but no
 * RTCP ever arrives within ~2 s of starting to send RTP, retry with the quirk values.
 */
interface PayloadTypeFallbackPolicy {
    /** How long to wait for the first inbound RTCP before deciding. */
    val probeWindowMs: Long

    /**
     * @param attempt 0 for the first negotiation, 1 after one retry, and so on.
     * @param rtcpReceived whether any RTCP arrived within [probeWindowMs].
     * @return true to renegotiate with the Android-TV payload types.
     */
    fun shouldRetryWithAndroidTvPayloadTypes(attempt: Int, rtcpReceived: Boolean): Boolean
}

/** The spec's default: one retry, if no RTCP arrived within 2 seconds. */
class DefaultPayloadTypeFallbackPolicy(
    override val probeWindowMs: Long = 2_000,
) : PayloadTypeFallbackPolicy {
    override fun shouldRetryWithAndroidTvPayloadTypes(attempt: Int, rtcpReceived: Boolean): Boolean =
        attempt == 0 && !rtcpReceived
}

/** A policy that never retries. Use when the receiver family is known-good. */
object NoPayloadTypeFallback : PayloadTypeFallbackPolicy {
    override val probeWindowMs: Long = 0
    override fun shouldRetryWithAndroidTvPayloadTypes(attempt: Int, rtcpReceived: Boolean): Boolean = false
}

/**
 * One rung of the launch ladder: an app id paired with the OFFER dialect it expects.
 *
 * @property appId the receiver application id to LAUNCH.
 * @property profile the OFFER dialect that app understands.
 */
data class LaunchRung(val appId: String, val profile: OfferProfile)

/**
 * Tunables for a mirroring session.
 *
 * Every codec/resolution/timing knob defaults to `null`, meaning "take it from the profile of
 * whichever ladder rung succeeded". Set one to pin it across both rungs.
 *
 * @property launchLadder rungs to try, most-preferred first. The default is what GMS does first
 *   (`674A0243` with the GMS OFFER dialect) followed by the Chrome/openscreen pairing as a
 *   safety net. GMS itself has **no** fallback — it launches exactly one app id — so reaching
 *   rung 2 is a signal worth logging rather than a routine event.
 * @property probeAppAvailability send `GET_APP_AVAILABILITY` before launching and skip ids the
 *   receiver reports unavailable. GMS does not probe; off by default to match, and because a
 *   `LAUNCH_ERROR{NOT_FOUND}` already drives the ladder.
 * @property requestCapabilities send `GET_CAPABILITIES` after connecting to the app. GMS never
 *   sends it. Informative only; **never** pick a mirroring codec from the response.
 * @property videoCodecs video codecs to offer, most-preferred first; `null` uses the profile's.
 * @property audioCodecs audio codecs to offer; `null` means **video only**.
 *
 *   GMS always offers audio, but system audio is not capturable on GrapheneOS the way GMS does
 *   it (`REMOTE_SUBMIX` needs `CAPTURE_AUDIO_OUTPUT`, the `AudioPolicy` route needs
 *   `MODIFY_AUDIO_ROUTING`; both are signature|privileged). Video-only is therefore the default,
 *   and every other field of the OFFER stays identical — the audio stream simply disappears and
 *   video moves to index 0.
 * @property resolutions offered capture resolutions; `null` uses the profile's.
 * @property maxFrameRate rational frame rate string; `null` uses the profile's.
 * @property maxBitRate video bitrate ceiling; `null` uses the profile's.
 * @property audioBitRate audio bitrate; `null` uses the profile's.
 * @property targetDelayMs end-to-end playout delay; `null` uses the profile's (100 ms for GMS).
 * @property receiverRtcpDscp DSCP to request, or `null` to omit. All streams must share one
 *   value; GMS omits the key entirely, which sidesteps the "Mixed DSCP values" rejection.
 * @property receiverRtcpEventLog request RTCP event logs; `null` uses the profile's.
 * @property connectParams the CONNECT payload shape; defaults to GMS's.
 * @property fallbackPolicy the Android-TV payload-type retry strategy. It only applies to
 *   profiles that do not already use those payload types, i.e. never to the GMS profile.
 * @property legacyTls enable old ciphers/TLSv1 for 2013-era receivers.
 * @property mediaTimeoutMs tear the session down after this long with no inbound RTCP once RTP is
 *   flowing; `0` disables the check. A receiver that answered the OFFER but never sends RTCP is
 *   not receiving media at all — AP client isolation, a firewalled UDP path or a rejected payload
 *   type all look like this — and without the timeout the session sits in
 *   [SessionState.STREAMING] forever in front of a black screen.
 */
data class SessionConfig(
    val launchLadder: List<LaunchRung> = DEFAULT_LAUNCH_LADDER,
    val probeAppAvailability: Boolean = false,
    val requestCapabilities: Boolean = false,
    val videoCodecs: List<String>? = null,
    val audioCodecs: List<String>? = null,
    val resolutions: List<Resolution>? = null,
    val maxFrameRate: String? = null,
    val maxBitRate: Int? = null,
    val audioBitRate: Int? = null,
    val targetDelayMs: Int? = null,
    val receiverRtcpDscp: Int? = null,
    val receiverRtcpEventLog: Boolean? = null,
    val connectParams: ConnectParams = ConnectParams.gms(),
    val fallbackPolicy: PayloadTypeFallbackPolicy = DefaultPayloadTypeFallbackPolicy(),
    val legacyTls: Boolean = false,
    val mediaTimeoutMs: Long = StreamingConstants.MEDIA_TIMEOUT_MS,
) {
    companion object {
        /** GMS's app id and dialect first, the Chrome/openscreen pairing as a fallback rung. */
        val DEFAULT_LAUNCH_LADDER = listOf(
            LaunchRung(CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO, OfferProfile.GMS_SYSTEM_MIRRORING),
            LaunchRung(CastAppIds.CAST_STREAMING_AUDIO_VIDEO, OfferProfile.OPENSCREEN_CHROME),
        )

        /** The same ladder for a Cast-for-audio device (`ca & 0x01 == 0`). */
        val AUDIO_ONLY_LAUNCH_LADDER = listOf(
            LaunchRung(CastAppIds.ANDROID_MIRRORING_AUDIO_ONLY, OfferProfile.GMS_SYSTEM_MIRRORING),
            LaunchRung(CastAppIds.CAST_STREAMING_AUDIO_ONLY, OfferProfile.OPENSCREEN_CHROME),
        )

        /**
         * Picks the ladder for a device, from its mDNS `ca` bitfield.
         *
         * This is exactly GMS's rule: any device with video output gets the A/V app id, and a
         * device with audio output but no video output gets the audio-only one.
         */
        fun ladderFor(hasVideoOutput: Boolean): List<LaunchRung> =
            if (hasVideoOutput) DEFAULT_LAUNCH_LADDER else AUDIO_ONLY_LAUNCH_LADDER
    }
}

/**
 * One negotiated stream: the OFFER stream the receiver accepted, paired with its receiver SSRC.
 *
 * @property offerStream the stream as offered.
 * @property receiverSsrc the receiver's SSRC for it, from the ANSWER.
 * @property sender the [Sender] driving it.
 */
class NegotiatedStream(
    val offerStream: OfferStream,
    val receiverSsrc: Long,
    val sender: Sender,
    /** The dialect this stream was offered in. */
    val profile: OfferProfile = OfferProfile.DEFAULT,
    /**
     * The resolution to encode at.
     *
     * Equal to the offered resolution unless the receiver's `display.dimensions` was smaller in
     * **both** axes, in which case GMS's rule replaces it. `null` for an audio stream.
     */
    val effectiveResolution: Resolution? = null,
) {
    /** True for the audio stream. */
    val isAudio: Boolean get() = offerStream.common.type == StreamType.AUDIO_SOURCE

    /** The negotiated codec name. */
    val codecName: String get() = offerStream.common.codecName

    /** The stream's RTP timebase: 90000 for video, the sample rate for audio. */
    val rtpTimebase: Int get() = offerStream.common.rtpTimebase

    /** The OFFER index the receiver accepted this stream under. */
    val index: Int get() = offerStream.common.index
}

/** Observes session progress. */
interface SessionListener {
    /** The session moved from [from] to [to]. */
    fun onStateChanged(from: SessionState, to: SessionState) {}

    /** The receiver's app is running; [status] names its `transportId` and `sessionId`. */
    fun onApplicationLaunched(appId: String, status: ReceiverStatus) {}

    /** Negotiation succeeded. */
    fun onNegotiated(answer: Answer, streams: List<NegotiatedStream>) {}

    /** `GET_CAPABILITIES` returned. */
    fun onCapabilities(capabilities: ReceiverCapabilities) {}

    /** The session ended; [cause] is `null` for a clean stop. */
    fun onEnded(cause: Throwable?) {}
}

/**
 * Orchestrates a full mirroring session: discovery result in, pixels out.
 *
 * ```
 * TLS connect -> CONNECT (sender-0 -> receiver-0)
 *             -> [GET_APP_AVAILABILITY]
 *             -> LAUNCH <appId> -> RECEIVER_STATUS -> transportId, sessionId
 *             -> CONNECT (sender-XXXX -> transportId)     <- the step everyone forgets
 *             -> [GET_CAPABILITIES]
 *             -> OFFER -> ANSWER -> udpPort, sendIndexes, ssrcs
 *             -> RTP/RTCP over UDP
 *             -> STOP + CLOSE
 * ```
 *
 * Entirely Android-free: sockets are injected, so the whole flow is exercisable in memory.
 *
 * @param host the receiver address.
 * @param port the receiver's Cast port, from the mDNS SRV record.
 * @param config session tunables.
 * @param frameSourceProvider builds the encoder-backed frame source for a negotiated stream. It
 *   is called again after a renegotiation, so implementations must be able to restart from frame
 *   id 0.
 * @param channelProvider builds the control channel; overridden in tests.
 * @param udpProvider builds the media transport; overridden in tests.
 */
class SessionController(
    val host: String,
    val port: Int = TlsCastSocket.DEFAULT_PORT,
    val config: SessionConfig = SessionConfig(),
    private val frameSourceProvider: (NegotiatedStream) -> FrameSource,
    private val channelProvider: (String, Int) -> CastChannel = { h, p ->
        CastChannel(
            TlsCastSocket.connect(h, p, legacyCiphers = config.legacyTls),
            connectParams = config.connectParams,
        )
    },
    private val udpProvider: (String, Int) -> UdpTransport = { h, p -> DatagramUdpTransport(h, p) },
) : AutoCloseable {

    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val stopped = AtomicBoolean(false)

    /** The current state. */
    @Volatile
    var state: SessionState = SessionState.IDLE
        private set

    /** The error that ended the session, if any. */
    @Volatile
    var failure: Throwable? = null
        private set

    /** The app id that was successfully launched. */
    @Volatile
    var launchedAppId: String? = null
        private set

    /** The OFFER dialect paired with [launchedAppId]. */
    @Volatile
    var activeProfile: OfferProfile = OfferProfile.DEFAULT
        private set

    /** The `sessionId` from `RECEIVER_STATUS`, used for `STOP`. */
    @Volatile
    var sessionId: String? = null
        private set

    /** The app's `transportId`, used as the OFFER/ANSWER destination. */
    @Volatile
    var transportId: String? = null
        private set

    /** The most recent ANSWER. */
    @Volatile
    var answer: Answer? = null
        private set

    /** The negotiated streams. */
    @Volatile
    var streams: List<NegotiatedStream> = emptyList()
        private set

    /** The media packet router, once negotiation has completed. */
    @Volatile
    var router: SenderPacketRouter? = null
        private set

    private var channel: CastChannel? = null
    private var messenger: SessionMessenger? = null
    private var sessionSenderId: String = Namespaces.PLATFORM_SENDER_ID
    private var transport: UdpTransport? = null
    private val pumpThreads = ArrayList<Thread>()
    private val frameSources = ArrayList<FrameSource>()
    private var negotiationAttempt = 0
    private var mediaHost: String = host

    /** Monotonic reading at which RTP started flowing; the media-timeout baseline. */
    @Volatile
    private var streamingSinceMs: Long = 0

    /** Registers a progress listener. */
    fun addListener(l: SessionListener) { listeners.add(l) }

    /**
     * Runs the whole bring-up sequence and starts streaming.
     *
     * Blocks until RTP is flowing (or the session fails). Frame pumping continues on background
     * threads afterwards.
     *
     * @throws SessionException if any step fails.
     */
    fun start() {
        try {
            connect()
            launch()
            negotiate(activeProfile, androidTvPayloadTypeHack = false)
            startStreaming()
            maybeApplyPayloadTypeFallback()
        } catch (e: Throwable) {
            // stop() tears the media path down, sends STOP/CLOSE if an app was launched, moves to
            // FAILED and notifies listeners.
            stop(e)
            if (e is SessionException) throw e
            throw SessionException(state, "session failed: ${e.message}", e)
        }
    }

    // ---- phase 1: control channel -----------------------------------------------------------

    private fun connect() {
        transition(SessionState.CONNECTING)
        val ch = channelProvider(host, port)
        channel = ch
        ch.addListener(object : CastChannelListener {
            override fun onReceiverStatus(status: ReceiverStatus) = onReceiverStatusChanged(status)
            override fun onVirtualConnectionClosed(sourceId: String) {
                // A CLOSE from the app's transportId means the receiver ended the session.
                if (sourceId == transportId) stop(SessionException(state, "receiver closed the session"))
            }
            override fun onClosed(cause: Throwable?) {
                if (state == SessionState.STREAMING || state == SessionState.NEGOTIATING) {
                    stop(cause ?: SessionException(state, "control channel closed"))
                }
            }
        })
        ch.start()
        // Virtual connection 1: platform control. Required before LAUNCH/GET_STATUS/STOP.
        ch.openVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)
        transition(SessionState.CONNECTED)
    }

    // ---- phase 2: launch --------------------------------------------------------------------

    private fun launch() {
        val ch = channel ?: throw SessionException(state, "no channel")
        transition(SessionState.LAUNCHING)

        var rungs = config.launchLadder
        if (config.probeAppAvailability) {
            // GMS never probes; this is an optional diagnostic. An id the receiver reports as
            // unavailable is skipped, but an empty or unanswered probe changes nothing.
            val availability = runCatching { ch.getAppAvailability(rungs.map { it.appId }) }
                .getOrDefault(emptyMap())
            if (availability.isNotEmpty()) {
                val available = rungs.filter {
                    availability[it.appId]?.equals(ReceiverMessageTypes.APP_AVAILABLE, ignoreCase = true) ?: true
                }
                if (available.isNotEmpty()) rungs = available
            }
        }

        var lastError: Throwable? = null
        for (rung in rungs) {
            val status = try {
                ch.launch(
                    rung.appId,
                    language = rung.profile.launchLanguage,
                    supportedAppTypes = rung.profile.launchSupportedAppTypes,
                )
            } catch (e: Throwable) {
                // LAUNCH_ERROR{NOT_FOUND} means this device does not know the app id, so fall to
                // the next rung. GMS has no ladder at all, so needing rung 2 is worth logging.
                lastError = e
                continue
            }
            // Some receivers answer LAUNCH with a RECEIVER_STATUS taken before the app has
            // finished registering its transportId, and only name it in a later status. Falling
            // straight through to the next ladder rung there would STOP an app that did launch
            // and try a different one, so poll GET_STATUS a few times first.
            var app = status.applicationFor(rung.appId)
            var polls = 0
            while (app?.transportId == null && polls < LAUNCH_STATUS_POLLS) {
                polls++
                try { Thread.sleep(LAUNCH_STATUS_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                app = runCatching { ch.getStatus().applicationFor(rung.appId) }.getOrNull()
            }
            if (app?.transportId == null) {
                lastError = SessionException(state, "RECEIVER_STATUS did not name ${rung.appId} with a transportId")
                continue
            }
            launchedAppId = rung.appId
            activeProfile = rung.profile
            transportId = app.transportId
            sessionId = app.sessionId
            listeners.forEach { runCatching { it.onApplicationLaunched(rung.appId, status) } }

            // Virtual connection 2: to the app itself. Skipping this makes the receiver silently
            // drop the OFFER, which is the single most common bug in a mirroring sender.
            sessionSenderId = CastChannel.uniqueSenderId()
            ch.openVirtualConnection(app.transportId, sessionSenderId)
            messenger = SessionMessenger(
                channel = ch,
                sourceId = sessionSenderId,
                destinationId = app.transportId,
                initialSeqNum = rung.profile.initialSeqNum,
            )
            return
        }
        throw SessionException(
            SessionState.LAUNCHING,
            "could not launch any of ${rungs.map { it.appId }}",
            lastError,
        )
    }

    // ---- phase 3: negotiate -----------------------------------------------------------------

    private fun negotiate(profile: OfferProfile, androidTvPayloadTypeHack: Boolean) {
        val m = messenger ?: throw SessionException(state, "no session messenger")
        transition(SessionState.NEGOTIATING)

        if (config.requestCapabilities) {
            m.getCapabilities()?.let { caps -> listeners.forEach { runCatching { it.onCapabilities(caps) } } }
        }

        val offer = OfferBuilder.build(
            profile = profile,
            videoCodecs = config.videoCodecs ?: profile.videoCodecs,
            // null means video-only: the audio stream simply disappears and video moves to
            // index 0, leaving every other field of the OFFER identical.
            audioCodecs = config.audioCodecs ?: emptyList(),
            resolutions = config.resolutions ?: profile.resolutions,
            maxFrameRate = config.maxFrameRate ?: profile.maxFrameRate,
            maxBitRate = config.maxBitRate ?: profile.maxBitRate,
            audioBitRate = config.audioBitRate ?: profile.audioBitRate,
            targetDelayMs = config.targetDelayMs ?: profile.targetDelayMs,
            androidTvPayloadTypeHack = androidTvPayloadTypeHack,
            receiverRtcpDscp = config.receiverRtcpDscp,
            receiverRtcpEventLog = config.receiverRtcpEventLog ?: profile.receiverRtcpEventLog,
        )

        when (val result = m.sendOffer(offer)) {
            is AnswerResult.Ok -> buildMediaPath(offer, result.answer)
            is AnswerResult.Error -> throw SessionException(
                SessionState.NEGOTIATING,
                "receiver rejected the OFFER: code=${result.error.code} ${result.error.description}",
            )
            is AnswerResult.Invalid -> throw SessionException(
                SessionState.NEGOTIATING,
                "malformed ANSWER: ${result.reason}",
            )
        }
    }

    private fun buildMediaPath(offer: Offer, a: Answer) {
        answer = a
        // GMS lets the receiver's advertised `ipaddr` override the control-channel address for
        // the media destination, which is what makes a multi-homed receiver reachable.
        mediaHost = a.ipaddr ?: host
        val udp = udpProvider(mediaHost, a.udpPort)
        transport = udp
        val r = SenderPacketRouter(udp)
        router = r

        val negotiated = ArrayList<NegotiatedStream>()
        for ((position, index) in a.sendIndexes.withIndex()) {
            val offered = offer.streamAtIndex(index) ?: continue
            val receiverSsrc = a.ssrcs.getOrNull(position) ?: continue
            val common = offered.common
            val sender = Sender(
                senderSsrc = common.ssrc,
                receiverSsrc = receiverSsrc,
                rtpPayloadType = common.rtpPayloadType,
                rtpTimebase = common.rtpTimebase,
                aesKey = common.aesKey,
                aesIvMask = common.aesIvMask,
                targetPlayoutDelayMs = common.targetDelayMs,
                isAudio = offered is AudioOfferStream,
            )
            negotiated.add(
                NegotiatedStream(
                    offerStream = offered,
                    receiverSsrc = receiverSsrc,
                    sender = sender,
                    profile = offer.profile,
                    effectiveResolution = (offered as? VideoOfferStream)
                        ?.resolutions?.firstOrNull()
                        ?.let { a.effectiveResolution(it) },
                )
            )
            r.addSender(sender)
        }
        if (negotiated.isEmpty()) {
            throw SessionException(SessionState.NEGOTIATING, "no offered stream matched the ANSWER's sendIndexes")
        }
        streams = negotiated
        listeners.forEach { runCatching { it.onNegotiated(a, negotiated) } }
    }

    // ---- phase 4: stream --------------------------------------------------------------------

    private fun startStreaming() {
        val r = router ?: throw SessionException(state, "no packet router")
        streamingSinceMs = monotonicMs()
        r.start()
        // Enter STREAMING before the pump threads start: they use the state as their run flag.
        transition(SessionState.STREAMING)
        for (ns in streams) {
            val source = frameSourceProvider(ns)
            frameSources.add(source)
            val t = Thread({ pump(ns, source) }, "cast-pump-${if (ns.isAudio) "audio" else "video"}")
            t.isDaemon = true
            pumpThreads.add(t)
            t.start()
        }
    }

    /**
     * True when RTP has been flowing for longer than [SessionConfig.mediaTimeoutMs] without a
     * single inbound RTCP datagram.
     *
     * Both readings are on the monotonic clock, which is what [SenderPacketRouter] records.
     */
    private fun mediaTimedOut(): Boolean {
        val timeout = config.mediaTimeoutMs
        if (timeout <= 0) return false
        val r = router ?: return false
        val last = r.lastRtcpArrivalMs
        val since = if (last != SenderPacketRouter.NEVER) last else streamingSinceMs
        return monotonicMs() - since > timeout
    }

    /**
     * Drives one stream: pull encoded frames and enqueue them, applying backpressure at the
     * encoder input rather than by discarding encoder output.
     *
     * ### Why video frames are never dropped after encoding
     *
     * An H.264 stream is a reference chain: frame N+1 is a delta against frame N. If the sender
     * sheds an *already-encoded* frame N (because its in-flight budget is momentarily full), the
     * receiver still gets a contiguous, renumbered id stream — so it reports no loss — but every
     * following P-frame now references content it never received, and the picture datamoshes until
     * the next key frame. Audio can be dropped like this; video cannot.
     *
     * So the pump holds at most one [pending] encoded frame and only pulls the next one once the
     * sender has room ([Sender.canEnqueue]). When the sender is full it simply stops draining the
     * encoder; MediaCodec then backpressures its input surface and the *capture* frames are dropped
     * pre-encode, which costs nothing — the encoded frames it does produce still form an unbroken
     * chain. Every encoded frame the encoder emits is transmitted, in order, with contiguous ids.
     */
    private fun pump(stream: NegotiatedStream, source: FrameSource) {
        var pending: EncodedFrame? = null
        while (!stopped.get() && state == SessionState.STREAMING) {
            if (mediaTimedOut()) {
                stop(
                    SessionException(
                        state,
                        "no RTCP from the receiver for ${config.mediaTimeoutMs} ms; " +
                            "the media path is not reaching $mediaHost",
                    )
                )
                return
            }
            if (stream.sender.needsKeyFrame()) runCatching { source.requestKeyFrame() }

            // Pull the next encoded frame only when not already holding one for enqueue.
            if (pending == null) {
                val expectedFrameId = stream.sender.nextFrameId()
                val frame = try {
                    source.nextFrame(100, expectedFrameId)
                } catch (e: Throwable) {
                    // A video source failure is fatal — video is the session. An AUDIO source
                    // failure is not: Tier A capture (AudioPlaybackCapture) can fail at any moment
                    // (no route, a format the encoder rejects, the app opting out of capture), and
                    // that must never tear down the video the user is watching. End just this pump;
                    // the audio Sender then sits idle (harmless), video keeps flowing.
                    if (stream.isAudio) { runCatching { source.close() }; return }
                    stop(e); return
                } ?: continue
                // A source that ignores the requested id would break the sender's contiguity check;
                // skip rather than crash the pump thread. With the contract honoured this never fires.
                if (frame.frameId != expectedFrameId) continue
                pending = frame
            }
            val frame = pending

            // Backpressure: if the sender is at its in-flight budget, do NOT discard this encoded
            // frame. Hold it and wait; not draining the encoder drops capture frames pre-encode.
            if (!stream.sender.canEnqueue(frame.rtpTimestamp)) {
                try { Thread.sleep(BACKPRESSURE_POLL_MS) } catch (_: InterruptedException) { return }
                continue
            }

            when (stream.sender.enqueue(frame)) {
                EnqueueResult.OK -> pending = null
                // Unreachable after canEnqueue() for the two pacing limits; reached only on a
                // genuine anomaly (a frame too large to packetize). An encoded frame is being lost,
                // so the reference chain is about to break — force an immediate key frame so the
                // corruption cannot outlive one GOP, and drop only this un-sendable frame.
                EnqueueResult.REACHED_ID_SPAN_LIMIT,
                EnqueueResult.MAX_DURATION_IN_FLIGHT,
                EnqueueResult.PAYLOAD_TOO_LARGE -> {
                    pending = null
                    runCatching { source.requestKeyFrame() }
                }
            }
        }
    }

    /**
     * Applies the Android-TV payload-type fallback if the policy asks for it.
     *
     * Waits [PayloadTypeFallbackPolicy.probeWindowMs] for the first inbound RTCP; if none arrives,
     * the media path is torn down and renegotiated with the quirk payload types.
     */
    private fun maybeApplyPayloadTypeFallback() {
        val policy = config.fallbackPolicy
        if (policy.probeWindowMs <= 0) return
        // The GMS dialect already uses those payload types on every stream, so there is nothing
        // to fall back to; the policy only applies to the openscreen dialect.
        if (activeProfile.androidTvPayloadTypes) return
        val r = router ?: return
        val deadline = System.currentTimeMillis() + policy.probeWindowMs
        while (System.currentTimeMillis() < deadline) {
            if (r.lastRtcpArrivalMs != SenderPacketRouter.NEVER) break
            Thread.sleep(25)
        }
        val rtcpReceived = r.lastRtcpArrivalMs != SenderPacketRouter.NEVER
        if (!policy.shouldRetryWithAndroidTvPayloadTypes(negotiationAttempt, rtcpReceived)) return

        negotiationAttempt++
        teardownMediaPath()
        negotiate(activeProfile, androidTvPayloadTypeHack = true)
        startStreaming()
    }

    // ---- teardown ---------------------------------------------------------------------------

    private fun onReceiverStatusChanged(status: ReceiverStatus) {
        val sid = sessionId ?: return
        if (state != SessionState.STREAMING && state != SessionState.NEGOTIATING) return
        // An unsolicited status that no longer lists our session means the user pressed Home or
        // another sender took over.
        val stillRunning = status.applications.any { it.sessionId == sid }
        if (!stillRunning && status.applications.isNotEmpty()) {
            stop(SessionException(state, "session terminated on the receiver"))
        }
    }

    private fun teardownMediaPath() {
        // Never interrupt the calling thread. A pump thread is a legitimate caller of stop() —
        // it is where an encoder failure and the media-inactivity timeout surface — and
        // self-interrupting would set its interrupt flag, so the very next blocking call in the
        // teardown (the channel's write queue) would throw InterruptedException and abandon the
        // session in STOPPING: no STOP, no CLOSE, no FAILED transition, no listener callback.
        val self = Thread.currentThread()
        pumpThreads.forEach { if (it !== self) it.interrupt() }
        pumpThreads.clear()
        frameSources.forEach { runCatching { it.close() } }
        frameSources.clear()
        router?.let { runCatching { it.close() } }
        router = null
        transport = null
        streams = emptyList()
    }

    /** Stops the session, sending `STOP` and closing everything. Idempotent. */
    @JvmOverloads
    fun stop(cause: Throwable? = null) {
        if (!stopped.compareAndSet(false, true)) return
        transition(SessionState.STOPPING)
        failure = cause
        runCatching { teardownMediaPath() }
        // Every step below is best-effort: a control channel that is already dead must not stop
        // the session from reaching a terminal state and notifying its listeners.
        val ch = channel
        runCatching { sessionId?.let { ch?.stop(it) } }
        runCatching { transportId?.let { ch?.closeVirtualConnection(it, sessionSenderId) } }
        runCatching { ch?.closeVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID) }
        runCatching { messenger?.close() }
        runCatching { ch?.close() }
        channel = null
        transition(if (cause == null) SessionState.TERMINATED else SessionState.FAILED)
        listeners.forEach { runCatching { it.onEnded(cause) } }
    }

    override fun close() = stop(null)

    private fun transition(to: SessionState) {
        val from = state
        if (from == to) return
        state = to
        listeners.forEach { runCatching { it.onStateChanged(from, to) } }
    }

    companion object {
        /** The monotonic millisecond clock the media timeout and the packet router share. */
        private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

        /** Poll interval while the sender is at its in-flight budget and the pump is backpressured. */
        const val BACKPRESSURE_POLL_MS = 2L

        /** How many `GET_STATUS` polls to make while waiting for a launched app's transportId. */
        const val LAUNCH_STATUS_POLLS = 5

        /** Delay between those polls, in milliseconds. */
        const val LAUNCH_STATUS_POLL_INTERVAL_MS = 300L
    }
}
