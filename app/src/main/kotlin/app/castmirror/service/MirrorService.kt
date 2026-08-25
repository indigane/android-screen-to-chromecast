package app.castmirror.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import app.castmirror.R
import app.castmirror.capture.LetterboxCompositor
import app.castmirror.capture.ScreenCapture
import app.castmirror.core.NegotiatedStream
import app.castmirror.core.SessionConfig
import app.castmirror.core.SessionController
import app.castmirror.core.SessionListener
import app.castmirror.core.SessionState
import app.castmirror.core.cast.channel.ConnectParams
import app.castmirror.core.cast.channel.ReceiverStatus
import app.castmirror.core.cast.streaming.FrameSource
import app.castmirror.core.cast.streaming.SenderObserver
import app.castmirror.core.cast.streaming.messages.Answer
import app.castmirror.core.cast.streaming.messages.Resolution
import app.castmirror.core.cast.streaming.BitrateSuggestor
import app.castmirror.capture.AudioPlaybackCapture
import app.castmirror.encode.AudioEncoderFrameSource
import app.castmirror.encode.EncodeSnapshot
import app.castmirror.encode.KeyFrameReason
import app.castmirror.encode.VideoEncoderFrameSource
import app.castmirror.settings.MirrorSettings
import app.castmirror.ui.MainActivity
import app.castmirror.util.AppLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A snapshot of what the service is doing, delivered to bound clients.
 *
 * @property active true while a session is being set up or is streaming.
 * @property deviceName the receiver's friendly name, when one is selected.
 * @property state the core's session state, or `null` before a session exists.
 * @property detail a one-line human summary, always populated.
 * @property stats a one-line rendering of the video sender's counters, when streaming.
 */
data class MirrorStatus(
    val active: Boolean,
    val deviceName: String?,
    val state: SessionState?,
    val detail: String,
    val stats: String? = null,
    /**
     * The stable key (`host:port`) of the receiver this session is or was using.
     *
     * The UI reconciles its selection against this so that, whenever a session is running, the
     * device shown as selected is exactly the streaming target — never a row the framework
     * re-checked from stale saved state.
     */
    val deviceKey: String? = null,
)

/**
 * The foreground service that owns a whole mirroring session.
 *
 * It sits between three things that all have opinions about lifetime:
 *
 *  * **The platform.** Since Android 14 a `MediaProjection` may only be obtained by a process that
 *    is *already* running a foreground service of type `mediaProjection`, and that service must
 *    reach the foreground within 10 seconds of being started. [onStartCommand] therefore calls
 *    `startForeground` before it touches [MediaProjectionManager], and everything slow happens on
 *    a worker thread afterwards. The consent token is also single-use from Android 14 onward, so
 *    the UI requests fresh consent for every session rather than caching an intent.
 *  * **The core.** [SessionController.start] blocks until RTP is flowing and then keeps pumping on
 *    its own threads, so it runs on [sessionThread] and reports back through [SessionListener].
 *  * **The user.** The notification carries a Stop action, and the screen-capture chip in the
 *    status bar can revoke the projection at any moment; both paths converge on [stopSession].
 */
class MirrorService : Service() {

    /** Handle for the activity to read state and drive the session. */
    inner class LocalBinder : Binder() {
        /** The bound service. */
        val service: MirrorService get() = this@MirrorService
    }

    /** Observes [MirrorStatus] changes. Callbacks arrive on the main thread. */
    fun interface StateListener {
        /** The service's state changed to [status]. */
        fun onMirrorStatus(status: MirrorStatus)
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<StateListener>()
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)

    private lateinit var settings: MirrorSettings
    private var captureThread: HandlerThread? = null
    private var sessionThread: Thread? = null
    private var statsThread: Thread? = null

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var compositor: LetterboxCompositor? = null
    private var controller: SessionController? = null
    private var encoder: VideoEncoderFrameSource? = null
    private var audioSource: AudioEncoderFrameSource? = null
    private var suggestor: BitrateSuggestor? = null
    private var diagnostics: SessionDiagnostics? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var deviceName: String? = null

    /** The `host:port` of the current session's receiver, mirrored into [MirrorStatus.deviceKey]. */
    private var targetKey: String? = null
    private var offeredResolution: Resolution = Resolution(1280, 720)

    /** The phone's captured resolution in the current rotation; drives the letterbox fit. */
    @Volatile
    private var sourceWidth = 0

    @Volatile
    private var sourceHeight = 0

    @Volatile
    private var status: MirrorStatus = MirrorStatus(false, null, null, "Idle")

    override fun onCreate() {
        super.onCreate()
        settings = MirrorSettings(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.i("service: stop requested")
                stopSession(null)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (isRunning) {
                    AppLog.w("service: a session is already running; ignoring the start request")
                    return START_NOT_STICKY
                }
                startSession(intent)
                return START_NOT_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopSession(null)
        super.onDestroy()
    }

    /**
     * Fallback rotation trigger.
     *
     * A [DisplayManager.DisplayListener] is the primary rotation signal (see [registerDisplayListener])
     * because it fires on every rotation, including a 180° flip that does not change the
     * [Configuration.orientation]. This override catches the residual cases and simply defers to
     * the same handler, which is idempotent when the size has not actually changed.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isRunning) applyRotation()
    }

    /**
     * Re-letterboxes the mirror after a rotation.
     *
     * The encode frame stays a fixed landscape size for the whole session — Cast cannot
     * renegotiate resolution — so rotation is handled entirely below it: the virtual display is
     * resized to the phone's new real resolution and the [LetterboxCompositor] is told the new
     * source size, so the next captured frames arrive at the correct aspect ratio and are
     * re-fitted into the fixed frame. A key frame is requested because the whole picture changes at
     * once, which is the case a delta frame handles worst.
     */
    private fun applyRotation() {
        val comp = compositor ?: return
        val (width, height) = currentDisplaySize()
        if (width <= 0 || height <= 0) return
        if (width == sourceWidth && height == sourceHeight) return
        sourceWidth = width
        sourceHeight = height
        AppLog.i("service: rotation -> capturing at ${width}x$height, re-letterboxing")
        comp.setSourceSize(width, height)
        capture?.resize(width, height)
        encoder?.requestKeyFrame(KeyFrameReason.ROTATION)
    }

    // ---- public surface for the activity ------------------------------------------------------

    /** Subscribes [l] and immediately delivers the current status. */
    fun addListener(l: StateListener) {
        listeners.add(l)
        val snapshot = status
        main.post { runCatching { l.onMirrorStatus(snapshot) } }
    }

    /** Unsubscribes [l]. */
    fun removeListener(l: StateListener) { listeners.remove(l) }

    /** The current status. */
    fun currentStatus(): MirrorStatus = status

    // ---- session lifecycle --------------------------------------------------------------------

    private fun startSession(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
        val hasVideoOutput = intent.getBooleanExtra(EXTRA_HAS_VIDEO_OUTPUT, true)

        if (host.isNullOrEmpty() || port !in 1..65535 || resultData == null) {
            AppLog.w("service: start request was missing the receiver address or the capture consent")
            publish(status.copy(detail = getString(R.string.error_start_request_incomplete)))
            stopSelf()
            return
        }

        // The stable key the UI selects by. Recorded before anything can fail so the selection can
        // always be reconciled against the exact streaming target, and kept in the companion so it
        // survives this session's teardown for the next time the UI binds.
        val key = "$host:$port"
        targetKey = key
        lastTargetKey = key

        stopping.set(false)
        isRunning = true
        acquireStreamingLocks()
        publish(MirrorStatus(true, deviceName, null, getString(R.string.status_starting), deviceKey = key))

        // Order matters: the foreground service must exist, with the mediaProjection type, before
        // getMediaProjection is called. Android 14 throws SecurityException otherwise. The
        // microphone type is added only when internal audio will really be captured.
        val useMic = settings.mirrorAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        enterForeground(getString(R.string.status_preparing), useMic)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Throwable) {
            AppLog.w("service: getMediaProjection failed", e)
            null
        }
        if (mp == null) {
            fail(getString(R.string.error_consent_rejected))
            return
        }
        projection = mp

        val thread = HandlerThread("castmirror-capture").apply { start() }
        captureThread = thread
        capture = ScreenCapture(mp, Handler(thread.looper))

        registerNetworkCallback()

        val t = Thread({ runSession(host, port, hasVideoOutput) }, "castmirror-session")
        t.isDaemon = true
        sessionThread = t
        t.start()
    }

    /**
     * Runs the blocking bring-up on a worker thread.
     *
     * Everything the user configured is turned into a [SessionConfig] here; the encoder itself is
     * not built until the receiver has answered, because only the ANSWER says what resolution the
     * receiver will actually accept.
     */
    private fun runSession(host: String, port: Int, hasVideoOutput: Boolean) {
        // The encode frame is fixed landscape for the whole session; rotation is handled by the
        // letterbox compositor, not by re-orienting the encoder (Cast cannot renegotiate).
        offeredResolution = settings.resolution.landscape()
        val codec = settings.codec
        val ladder = settings.appIdMode.ladder(hasVideoOutput)
        suggestor = BitrateSuggestor(settings.bitrateBps)
        diagnostics = SessionDiagnostics(settings.verboseDiagnostics)

        AppLog.i(
            "session: $host:$port codec=${codec.offerName} " +
                "${offeredResolution.width}x${offeredResolution.height}@${settings.frameRate} " +
                "${settings.bitrateKbps}kbps ladder=${ladder.map { it.appId }} " +
                "verbose=${settings.verboseDiagnostics}"
        )
        // The offered video codecs are the profile's, filtered to the single one the user chose.
        // VP8 only appears in the openscreen profile's list, so pairing it with the GMS app id
        // yields an OFFER the receiver has no matching stream for — the observed VP8 black screen.
        ladder.forEach { rung ->
            AppLog.i("session: rung ${rung.appId} (${rung.profile.cliName}) offers video ${rung.profile.videoCodecs}")
        }
        if (codec.offerName == "vp8" && ladder.none { it.profile.videoCodecs.contains("vp8") }) {
            AppLog.w(
                "session: VP8 selected but no ladder rung offers it — the GMS profile is h264-only, " +
                    "so the receiver will show a black screen. Use the openscreen app id, or H.264."
            )
        }

        // Decide the audio offer. The audio codec must match the dialect of the first ladder rung:
        // the GMS receiver takes AAC, the openscreen receiver takes Opus. If audio is off, the
        // permission is missing, or an Opus encoder is unavailable, the OFFER carries no audio
        // stream and the session mirrors video only — audio never fails the session.
        val audioProfile = ladder.first().profile
        val audioCodecName = audioProfile.audioCodec
        val audioCodecs: List<String>? = resolveAudioOffer(audioCodecName)

        val config = SessionConfig(
            launchLadder = ladder,
            videoCodecs = listOf(codec.offerName),
            audioCodecs = audioCodecs,
            audioBitRate = MirrorSettings.AUDIO_BITRATE_BPS,
            resolutions = listOf(offeredResolution),
            maxFrameRate = settings.frameRate.toString(),
            maxBitRate = settings.bitrateBps,
            connectParams = ConnectParams.gms(
                appPackage = packageName,
                senderVersion = appVersionName(),
                deviceModel = Build.MODEL,
                deviceProduct = Build.PRODUCT,
                androidRelease = Build.VERSION.RELEASE,
            ),
        )

        val session = SessionController(
            host = host,
            port = port,
            config = config,
            frameSourceProvider = ::createFrameSource,
        )
        controller = session
        session.addListener(object : SessionListener {
            override fun onStateChanged(from: SessionState, to: SessionState) {
                AppLog.i("session: $from -> $to")
                publish(status.copy(state = to, detail = describe(to)))
                if (to == SessionState.STREAMING) {
                    updateNotification(getString(R.string.status_streaming, deviceName ?: host))
                }
            }

            override fun onApplicationLaunched(appId: String, status: ReceiverStatus) {
                AppLog.i("session: launched $appId (sessionId=${status.applicationFor(appId)?.sessionId})")
            }

            override fun onNegotiated(answer: Answer, streams: List<NegotiatedStream>) {
                AppLog.i("session: ANSWER udpPort=${answer.udpPort} sendIndexes=${answer.sendIndexes} ssrcs=${answer.ssrcs}")
                answer.display?.dimensions?.let { AppLog.i("session: receiver panel is $it") }
                if (streams.none { !it.isAudio }) {
                    AppLog.w("session: the ANSWER accepted no video stream — nothing will appear on the TV")
                }
                for (s in streams) {
                    AppLog.i(
                        "session: stream index=${s.index} ${s.codecName} " +
                            "encode=${s.effectiveResolution ?: offeredResolution}"
                    )
                }
                attachDiagnostics(session)
            }

            override fun onEnded(cause: Throwable?) {
                if (cause == null) AppLog.i("session: ended cleanly")
                else AppLog.w("session: ended", cause)
                if (!stopping.get()) {
                    val reason = cause?.message ?: getString(R.string.error_session_ended)
                    main.post { stopSession(reason) }
                }
            }
        })

        try {
            session.start()
            startStatsLoop(session)
        } catch (e: Throwable) {
            AppLog.w("session: bring-up failed", e)
            main.post { fail(e.message ?: getString(R.string.status_failed)) }
        }
    }

    /**
     * Builds the encoder for a negotiated stream and points the virtual display at it.
     *
     * Called by the core once negotiation completes, on the session thread. The encode size comes
     * from [NegotiatedStream.effectiveResolution] — the receiver's panel can be smaller than what
     * was offered, and encoding larger than it wastes bitrate the receiver will only downscale.
     */
    /**
     * Decides whether to offer an audio stream, and logs the outcome.
     *
     * @param codecName the audio codec for the chosen dialect (`aac` or `opus`).
     * @return the single-codec list to put in the OFFER, or `null` for video-only.
     */
    private fun resolveAudioOffer(codecName: String): List<String>? {
        if (!settings.mirrorAudio) {
            AppLog.i("audio: off")
            return null
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("audio: RECORD_AUDIO denied → video only")
            return null
        }
        val mime = runCatching { AudioEncoderFrameSource.mimeFor(codecName) }.getOrNull()
        if (mime == null || !AudioEncoderFrameSource.hasEncoder(mime)) {
            AppLog.w("audio: unavailable (no encoder for $codecName)")
            return null
        }
        val framing = if (codecName.equals("aac", ignoreCase = true) && settings.aacAdts) "ADTS " else ""
        AppLog.i("audio: $codecName ${framing}48kHz stereo ${MirrorSettings.AUDIO_BITRATE_BPS / 1000}kbps")
        return listOf(codecName)
    }

    private fun createFrameSource(stream: NegotiatedStream): FrameSource {
        if (stream.isAudio) return createAudioFrameSource(stream)

        // The encode frame is the negotiated resolution, forced landscape and rounded to even
        // dimensions. It is fixed for the session; the compositor fits the rotating screen into it.
        val encode = orientLandscape(stream.effectiveResolution ?: offeredResolution)
        val encodeWidth = encode.width and EVEN_MASK
        val encodeHeight = encode.height and EVEN_MASK
        val source = VideoEncoderFrameSource(
            codecName = stream.codecName,
            width = encodeWidth,
            height = encodeHeight,
            initialBitRate = settings.bitrateBps,
            frameRate = settings.frameRate,
            keyFrameIntervalSeconds = settings.keyFrameIntervalSeconds,
            rtpTimebase = stream.rtpTimebase,
            errorRecoveryMode = stream.profile.errorRecoveryMode,
        )
        encoder = source

        // The virtual display captures at the phone's real resolution so the content keeps its
        // natural aspect ratio; the compositor letterboxes that into the fixed encode frame.
        val (srcW, srcH) = currentDisplaySize().let { (w, h) ->
            if (w > 0 && h > 0) w to h else encodeWidth to encodeHeight
        }
        sourceWidth = srcW
        sourceHeight = srcH
        val comp = try {
            LetterboxCompositor(
                outputSurface = source.inputSurface,
                encodeWidth = encodeWidth,
                encodeHeight = encodeHeight,
                initialSourceWidth = srcW,
                initialSourceHeight = srcH,
            )
        } catch (e: Throwable) {
            // The core never took ownership of this encoder, so release it here before the failure
            // unwinds through startStreaming into the session's own teardown.
            runCatching { source.close() }
            encoder = null
            throw e
        }
        compositor = comp

        val screen = capture ?: throw IllegalStateException("screen capture was released before negotiation finished")
        screen.start(comp.inputSurface, srcW, srcH) { reason ->
            val message = getString(
                when (reason) {
                    ScreenCapture.StopReason.PROJECTION_STOPPED -> R.string.error_capture_stopped
                    ScreenCapture.StopReason.DISPLAY_STOPPED -> R.string.error_display_stopped
                }
            )
            main.post { stopSession(message) }
        }
        registerDisplayListener()
        return source
    }

    /**
     * Builds the audio capture + encoder for a negotiated audio stream.
     *
     * Reuses the video [MediaProjection][android.media.projection.MediaProjection] — no second
     * consent. Any failure here degrades to a silent source and is logged: audio must never fail
     * the session, which is already carrying video.
     */
    private fun createAudioFrameSource(stream: NegotiatedStream): FrameSource {
        val mp = projection ?: run {
            AppLog.w("audio: projection gone before audio setup → video only")
            return SilentFrameSource
        }
        return try {
            val audioCapture = AudioPlaybackCapture(mp, sampleRate = stream.rtpTimebase.coerceAtLeast(8_000))
            audioCapture.start()
            val source = try {
                AudioEncoderFrameSource(
                    capture = audioCapture,
                    codecName = stream.codecName,
                    bitRate = MirrorSettings.AUDIO_BITRATE_BPS,
                    rtpTimebase = stream.rtpTimebase,
                    adtsEnabled = settings.aacAdts,
                )
            } catch (e: Throwable) {
                runCatching { audioCapture.release() }
                throw e
            }
            audioSource = source
            source
        } catch (e: Throwable) {
            AppLog.w("audio: capture/encoder setup failed → video only", e)
            SilentFrameSource
        }
    }

    /**
     * Wires the diagnostics aggregator to the core's RTCP path.
     *
     * The router's per-datagram feedback hook drives receiver-report / checkpoint / NACK / PLI
     * logging, and the video sender's observer drives retransmission-burst logging. Both are set
     * before `router.start()` runs, inside the `onNegotiated` callback.
     */
    private fun attachDiagnostics(session: SessionController) {
        val diag = diagnostics ?: return
        val audioSender = session.streams.firstOrNull { it.isAudio }?.sender
        diag.audioSsrc = audioSender?.senderSsrc
        session.router?.onFeedback = { sender, feedback ->
            diag.onFeedback(isAudio = audioSender != null && sender === audioSender, feedback)
        }
        session.streams.firstOrNull { !it.isAudio }?.sender?.observer = object : SenderObserver {
            override fun onPacketsRetransmitted(count: Int) = diag.onRetransmitted(count)
        }
    }

    /** Returns [resolution] oriented so that width is the long edge. */
    private fun orientLandscape(resolution: Resolution): Resolution =
        if (resolution.height > resolution.width) Resolution(resolution.height, resolution.width)
        else resolution

    /** The phone's real display size in the current rotation, or `0x0` if it cannot be read. */
    private fun currentDisplaySize(): Pair<Int, Int> = try {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        point.x to point.y
    } catch (e: Throwable) {
        AppLog.w("service: could not read the display size", e)
        0 to 0
    }

    /**
     * Samples the video sender's counters once a second.
     *
     * Two things come out of it: a status line for the UI and the notification, and the congestion
     * signal that drives [BitrateSuggestor]. The core deliberately exposes no bandwidth estimate,
     * so dropped frames and kickstarts are all there is to work with — which is exactly what GMS
     * uses too.
     */
    private fun startStatsLoop(session: SessionController) {
        val t = Thread({ runStatsLoop(session) }, "castmirror-stats")
        t.isDaemon = true
        statsThread = t
        t.start()
    }

    private fun runStatsLoop(session: SessionController) {
        var lastTickMs = System.nanoTime() / 1_000_000L
        while (!stopping.get() && session.state == SessionState.STREAMING) {
            val stream = session.streams.firstOrNull { !it.isAudio }
            if (stream != null) {
                val s = stream.sender.stats()

                // Drive the bitrate controller ONLY from real RTCP congestion signals — the
                // receiver's fraction-lost and the smoothed RTT — never from the local
                // framesDropped/kickstarts counters, which reflect sender-side pacing rather than
                // network loss and would falsely throttle a loss-free stream.
                suggestor?.onFeedback(s.fractionLostNumerator, s.roundTripTimeMs)?.let { bitrate ->
                    AppLog.i("session: bitrate suggestion ${bitrate / 1000} kbps (loss=${s.fractionLostNumerator}/256 rtt=${s.roundTripTimeMs}ms)")
                    encoder?.requestBitrate(bitrate)
                }

                val now = System.nanoTime() / 1_000_000L
                val elapsed = now - lastTickMs
                lastTickMs = now
                val encodeSnapshot = encoder?.snapshot() ?: EncodeSnapshot(0, 0, 0, 0)
                val audioStats = session.streams.firstOrNull { it.isAudio }?.sender?.stats()
                val audioSnapshot = audioSource?.snapshot()
                val line = diagnostics?.logStatsTick(
                    s, encodeSnapshot, elapsed, suggestor?.current, audioStats, audioSnapshot,
                ) ?: "${s.framesEnqueued} frames, rtt ${s.roundTripTimeMs} ms"
                publish(status.copy(stats = line))
            }
            try {
                Thread.sleep(STATS_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * Ends the session and the service. Idempotent, and safe to call from any thread.
     *
     * The actual teardown runs on its own thread because it closes sockets and joins the core's
     * channel threads, and the two callers that matter most — the notification's Stop action and
     * the `MediaProjection` revocation callback — both arrive on the main thread.
     */
    fun stopSession(reason: String?) {
        if (!stopping.compareAndSet(false, true)) return
        isRunning = false
        AppLog.i("service: stopping${reason?.let { " ($it)" } ?: ""}")

        val screen = capture
        val comp = compositor
        val session = controller
        val audio = audioSource
        val worker = captureThread
        capture = null
        compositor = null
        controller = null
        encoder = null
        audioSource = null
        suggestor = null
        diagnostics = null
        projection = null
        captureThread = null
        sessionThread = null

        statsThread?.interrupt()
        statsThread = null
        unregisterNetworkCallback()
        unregisterDisplayListener()
        releaseStreamingLocks()

        val teardownThread = Thread({ teardown(screen, comp, audio, session, worker) }, "castmirror-teardown")
        teardownThread.isDaemon = true
        teardownThread.start()

        publish(MirrorStatus(false, deviceName, null, reason ?: getString(R.string.status_stopped), deviceKey = targetKey))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Releases the capture pipeline and the session in the order that avoids writing into released
     * objects.
     *
     * The virtual display goes first so the display server stops rendering into the compositor's
     * surface; the compositor follows, tearing down its GL context and the surface the display was
     * writing to; then the controller closes the frame sources (releasing the encoder) and sends
     * `STOP` + `CLOSE` on the control channel. Releasing the encoder before the compositor would
     * pull the compositor's EGL window surface out from under it.
     */
    private fun teardown(
        screen: ScreenCapture?,
        comp: LetterboxCompositor?,
        audio: AudioEncoderFrameSource?,
        session: SessionController?,
        worker: HandlerThread?,
    ) {
        runCatching { screen?.release() }.onFailure { AppLog.w("service: releasing capture failed", it) }
        runCatching { comp?.release() }.onFailure { AppLog.w("service: releasing the compositor failed", it) }
        // Releasing the audio source stops its reader thread and releases the AudioRecord + codec.
        // The controller also closes it via the frame-source contract; both are idempotent.
        runCatching { audio?.close() }.onFailure { AppLog.w("service: releasing audio failed", it) }
        runCatching { session?.stop() }.onFailure { AppLog.w("service: stopping the session failed", it) }
        runCatching { worker?.quitSafely() }
    }

    // ---- rotation tracking --------------------------------------------------------------------

    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager? ?: return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && isRunning) main.post { applyRotation() }
            }
        }
        runCatching { dm.registerDisplayListener(l, main) }
            .onSuccess { displayListener = l }
            .onFailure { AppLog.w("service: could not watch for rotation", it) }
    }

    private fun unregisterDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager? ?: return
        displayListener?.let { runCatching { dm.unregisterDisplayListener(it) } }
        displayListener = null
    }

    private fun fail(message: String) {
        publish(status.copy(detail = message))
        stopSession(message)
    }

    // ---- network ------------------------------------------------------------------------------

    /**
     * Ends the session when the default network goes away.
     *
     * Without this the control channel's socket would sit in a half-open state until its read
     * times out, and the user would watch a frozen picture for tens of seconds before anything
     * said why.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!isRunning) return
                AppLog.w("service: the default network was lost")
                val message = getString(R.string.error_network_lost)
                main.post { stopSession(message) }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { AppLog.w("service: could not watch the network", it) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    // ---- power / radio locks ------------------------------------------------------------------

    /**
     * Holds a Wi-Fi lock and a partial wake lock for the streaming session.
     *
     * Real-time RTP over UDP does not survive Wi-Fi power save: with the screen off or the device
     * idle the radio parks between beacons, and the tens of milliseconds of added latency and the
     * dropped datagrams show up as a frozen or blocky picture. A high-performance (or, on Android
     * 10+, low-latency) Wi-Fi lock keeps the radio awake, and a partial wake lock keeps the CPU
     * running the encoder and the pacer while the screen is off. Both are released in
     * [releaseStreamingLocks], which every teardown path reaches through [stopSession].
     */
    private fun acquireStreamingLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        if (wifi != null && wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching {
                wifi.createWifiLock(mode, "castmirror:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { AppLog.w("service: could not acquire the Wi-Fi lock", it) }.getOrNull()
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager?
        if (power != null && wakeLock == null) {
            wakeLock = runCatching {
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "castmirror:cpu").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }.onFailure { AppLog.w("service: could not acquire the wake lock", it) }.getOrNull()
        }
    }

    private fun releaseStreamingLocks() {
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    // ---- notification -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /**
     * Enters the foreground with the media-projection type, adding the microphone type only when
     * internal audio will actually be captured.
     *
     * The microphone FGS type may only be declared at `startForeground` when `RECORD_AUDIO` is
     * granted — declaring it without the grant throws on Android 14 — so [withMic] must reflect the
     * real decision, not merely the setting.
     */
    private fun enterForeground(text: String, withMic: Boolean) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(content)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.action_stop), stop).build())
            .build()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun publish(next: MirrorStatus) {
        status = next
        main.post { listeners.forEach { runCatching { it.onMirrorStatus(next) } } }
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1"

    private fun describe(state: SessionState): String = when (state) {
        SessionState.IDLE -> getString(R.string.status_idle)
        SessionState.CONNECTING -> getString(R.string.status_connecting)
        SessionState.CONNECTED -> getString(R.string.status_connected)
        SessionState.LAUNCHING -> getString(R.string.status_launching)
        SessionState.NEGOTIATING -> getString(R.string.status_negotiating)
        SessionState.STREAMING -> deviceName
            ?.let { getString(R.string.status_streaming, it) }
            ?: getString(R.string.status_streaming_generic)
        SessionState.STOPPING -> getString(R.string.status_stopping)
        SessionState.TERMINATED -> getString(R.string.status_stopped)
        SessionState.FAILED -> getString(R.string.status_failed)
    }

    /** A frame source that never produces anything, for a stream this app cannot feed. */
    private object SilentFrameSource : FrameSource {
        override fun nextFrame(timeoutMs: Long, nextFrameId: Int) = null
    }

    companion object {
        /** Starts a session. Requires every `EXTRA_*` below. */
        const val ACTION_START = "app.castmirror.action.START"

        /** Stops the running session and the service. */
        const val ACTION_STOP = "app.castmirror.action.STOP"

        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_DEVICE_NAME = "device_name"
        private const val EXTRA_HAS_VIDEO_OUTPUT = "has_video_output"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 1
        private const val STATS_INTERVAL_MS = 1_000L

        /**
         * Safety cap on the wake lock (4 hours).
         *
         * The lock is released deterministically on every teardown path, and the OS reclaims it if
         * the process dies; this bounds the worst case should a teardown path ever be missed.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1_000L

        /**
         * Mask that rounds a dimension down to an even number (`0xFFFFFFFE`).
         *
         * Every block-based video encoder needs even dimensions, and a receiver is free to answer
         * with an odd `display.dimensions`.
         */
        private const val EVEN_MASK = -2

        /**
         * The `host:port` of the most recent session's receiver, or `null` if none has run this
         * process.
         *
         * Survives a session's teardown so the UI can, on returning to the foreground, reconcile
         * its selection with the exact device that was streamed to rather than trust framework
         * view-state restoration.
         */
        @Volatile
        var lastTargetKey: String? = null
            private set

        /** True while a session is being set up or is streaming. Read by the quick-settings tile. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Builds the intent that starts a session.
         *
         * @param context any context.
         * @param host the receiver address.
         * @param port the receiver's Cast port, from the mDNS SRV record.
         * @param deviceName the friendly name, for the notification.
         * @param hasVideoOutput the device's `ca & 0x01` bit, which picks the app id.
         * @param resultCode the result code from the screen-capture consent dialog.
         * @param resultData the consent intent. Single-use from Android 14 onward.
         */
        fun startIntent(
            context: Context,
            host: String,
            port: Int,
            deviceName: String,
            hasVideoOutput: Boolean,
            resultCode: Int,
            resultData: Intent,
        ): Intent = Intent(context, MirrorService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)
            .putExtra(EXTRA_DEVICE_NAME, deviceName)
            .putExtra(EXTRA_HAS_VIDEO_OUTPUT, hasVideoOutput)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)

        /** Builds the intent that stops a running session. */
        fun stopIntent(context: Context): Intent =
            Intent(context, MirrorService::class.java).setAction(ACTION_STOP)
    }
}
