package app.castmirror.core

import app.castmirror.core.StreamingConstants
import app.castmirror.core.cast.channel.CastChannel
import app.castmirror.core.cast.channel.CastMessage
import app.castmirror.core.cast.channel.CastMessageFramer
import app.castmirror.core.cast.channel.CastSocket
import app.castmirror.core.cast.channel.Namespaces
import app.castmirror.core.cast.channel.ReceiverStatus
import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.FrameIds
import app.castmirror.core.cast.streaming.FakeUdpTransport
import app.castmirror.core.cast.streaming.FrameSource
import app.castmirror.core.cast.streaming.crypto.FrameCrypto
import app.castmirror.core.cast.streaming.rtp.RtpDefines
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.rtcp.RtcpDefines
import app.castmirror.core.util.Bytes
import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A [CastSocket] over a pair of in-memory pipes. */
private class PipeCastSocket(
    override val input: InputStream,
    override val output: OutputStream,
    override val description: String,
) : CastSocket {
    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/**
 * A minimal fake Chromecast speaking the real wire protocol over pipes.
 *
 * It answers CONNECT (silently), PING, GET_APP_AVAILABILITY, LAUNCH and OFFER, which is exactly
 * the set [SessionController] needs to reach STREAMING.
 */
private class FakeReceiver(
    private val input: InputStream,
    private val output: OutputStream,
    val appId: String = CastAppIds.CAST_STREAMING_AUDIO_VIDEO,
    val udpPort: Int = 33533,
    /**
     * Number of leading `RECEIVER_STATUS` messages that report **no** running application.
     *
     * Real receivers sometimes answer LAUNCH with a status snapshot taken before the app has
     * registered its `transportId`, naming it only in a later status. A sender that treats that
     * first status as "the launch failed" would STOP an app that did in fact launch and move
     * down its ladder.
     */
    private val statusesWithoutApp: Int = 0,
    /** When true, the ANSWER also accepts the offered `audio_source` stream (not just video). */
    private val acceptAudio: Boolean = false,
) {
    val transportId = "154d8823-4c0f-4a6f-a2b9-1e9e1c9d0e11"
    val sessionId = transportId

    /** The OFFER index of the accepted audio stream, or -1 if none. */
    @Volatile
    var audioIndex: Int = -1

    /** The receiver SSRC handed back for the audio stream. */
    @Volatile
    var audioReceiverSsrc: Long = 0

    /** Everything the sender sent, for assertions. */
    val received = java.util.Collections.synchronizedList(ArrayList<CastMessage>())

    /** The `supportedStreams` array from the OFFER the sender sent. */
    @Volatile
    var offeredStreams: Json.Arr? = null

    /** The OFFER index the fake receiver accepted. */
    @Volatile
    var acceptedIndex: Int = -1

    /** The receiver SSRC handed back in the ANSWER. */
    @Volatile
    var receiverSsrc: Long = 0

    /** The `seqNum` on the OFFER the sender sent. */
    @Volatile
    var offerSeqNum: Int = -1

    /** The Cast v2 `requestId` on the OFFER the sender sent. */
    @Volatile
    var offerRequestId: Int? = null

    /** The `offer.udpPort` key, when present. */
    @Volatile
    var offerUdpPort: Int? = null

    private val running = AtomicBoolean(true)
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "fake-receiver").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private fun send(msg: CastMessage) {
        synchronized(output) {
            output.write(msg.encodeFramed())
            output.flush()
        }
    }

    private fun reply(to: CastMessage, payload: Json.Obj, namespace: String = to.namespace) =
        send(
            CastMessage(
                sourceId = to.destinationId,
                destinationId = to.sourceId,
                namespace = namespace,
                payloadUtf8 = payload.encode(),
            )
        )

    private fun loop() {
        val framer = CastMessageFramer()
        val buf = ByteArray(8192)
        try {
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) return
                framer.append(buf, 0, n)
                while (true) {
                    val msg = framer.next() ?: break
                    received.add(msg)
                    handle(msg)
                }
            }
        } catch (_: Exception) {
            // The pipe closed; the session is over.
        }
    }

    private fun handle(msg: CastMessage) {
        val payload = msg.payloadUtf8?.let { Json.parseOrNull(it) }?.asObjOrNull() ?: return
        val type = payload["type"]?.asStringOrNull() ?: return
        val requestId = payload["requestId"]?.asIntOrNull() ?: 0

        when (msg.namespace) {
            Namespaces.HEARTBEAT -> if (type == "PING") reply(msg, jsonObj { put("type", "PONG") })

            Namespaces.CONNECTION -> Unit  // Real receivers stay silent, or send CONNECTED.

            Namespaces.RECEIVER -> when (type) {
                "GET_APP_AVAILABILITY" -> reply(
                    msg,
                    jsonObj {
                        put("responseType", "GET_APP_AVAILABILITY")
                        put("requestId", requestId)
                        put(
                            "availability",
                            jsonObj {
                                val ids = payload["appId"]?.asArrOrNull()?.items?.mapNotNull { it.asStringOrNull() }
                                    ?: emptyList()
                                for (id in ids) put(id, if (id == appId) "APP_AVAILABLE" else "APP_UNAVAILABLE")
                            }
                        )
                    },
                )

                "LAUNCH" -> {
                    val wanted = payload["appId"]?.asStringOrNull()
                    if (wanted != appId) {
                        reply(
                            msg,
                            jsonObj {
                                put("responseType", "LAUNCH_ERROR")
                                put("requestId", requestId)
                                put("reason", "NOT_FOUND")
                            },
                        )
                        return
                    }
                    // A real receiver often sends LAUNCH_STATUS first; the sender must not treat
                    // it as the final reply.
                    reply(
                        msg,
                        jsonObj {
                            put("responseType", "LAUNCH_STATUS")
                            put("requestId", requestId)
                            put("launchRequestId", requestId)
                            put("status", "USER_ALLOWED")
                        },
                    )
                    reply(msg, receiverStatus(requestId))
                }

                "GET_STATUS" -> reply(msg, receiverStatus(requestId))

                "STOP" -> reply(
                    msg,
                    jsonObj {
                        put("responseType", "RECEIVER_STATUS")
                        put("requestId", requestId)
                        put("status", jsonObj { put("applications", Json.Arr()) })
                    },
                )
            }

            Namespaces.WEBRTC -> if (type.uppercase() == "OFFER") handleOffer(msg, payload)
        }
    }

    /** How many `RECEIVER_STATUS` messages have been produced so far. */
    private val statusCount = AtomicInteger(0)

    private fun receiverStatus(requestId: Int): Json.Obj = jsonObj {
        put("responseType", "RECEIVER_STATUS")
        put("requestId", requestId)
        if (statusCount.getAndIncrement() < statusesWithoutApp) {
            put("status", jsonObj { put("applications", Json.Arr()) })
            return@jsonObj
        }
        put(
            "status",
            jsonObj {
                put(
                    "applications",
                    Json.Arr(
                        mutableListOf(
                            jsonObj {
                                put("appId", appId)
                                put("displayName", "Chrome Mirroring")
                                put("sessionId", sessionId)
                                put("transportId", transportId)
                                put("isIdleScreen", false)
                                put("statusText", "Chrome Mirroring")
                                put(
                                    "namespaces",
                                    Json.Arr(
                                        mutableListOf(
                                            jsonObj { put("name", Namespaces.WEBRTC) },
                                            jsonObj { put("name", Namespaces.MEDIA) },
                                        )
                                    )
                                )
                            }
                        )
                    )
                )
                put("volume", jsonObj { put("controlType", "attenuation"); put("level", 1.0); put("muted", false) })
            }
        )
    }

    private fun handleOffer(msg: CastMessage, payload: Json.Obj) {
        val seqNum = payload["seqNum"]?.asIntOrNull() ?: -1
        offerSeqNum = seqNum
        offerRequestId = payload["requestId"]?.asIntOrNull()
        val offerBody = payload["offer"]?.asObjOrNull()
        offerUdpPort = offerBody?.get("udpPort")?.asIntOrNull()
        val streams = offerBody?.get("supportedStreams")?.asArrOrNull()
        offeredStreams = streams
        // Pick the first video stream, exactly as a video-capable receiver would.
        val video = streams?.items?.mapNotNull { it.asObjOrNull() }
            ?.firstOrNull { it["type"]?.asStringOrNull() == "video_source" }
        if (video == null) {
            reply(
                msg,
                jsonObj {
                    put("type", "ANSWER")
                    put("seqNum", seqNum)
                    put("result", "error")
                    put("error", jsonObj { put("code", 42); put("description", "no video stream offered") })
                },
            )
            return
        }
        acceptedIndex = video["index"]!!.asIntOrNull()!!
        // The reference receiver uses offered_ssrc + 1.
        receiverSsrc = video["ssrc"]!!.asLongOrNull()!! + 1

        // Optionally accept the audio stream too, in OFFER order (audio index first, as offered).
        val audio = if (acceptAudio) {
            streams?.items?.mapNotNull { it.asObjOrNull() }
                ?.firstOrNull { it["type"]?.asStringOrNull() == "audio_source" }
        } else null
        if (audio != null) {
            audioIndex = audio["index"]!!.asIntOrNull()!!
            audioReceiverSsrc = audio["ssrc"]!!.asLongOrNull()!! + 1
        }
        val sendIndexes = if (audio != null) listOf(audioIndex, acceptedIndex) else listOf(acceptedIndex)
        val ssrcList: MutableList<Json> =
            if (audio != null) mutableListOf(Json.of(audioReceiverSsrc), Json.of(receiverSsrc))
            else mutableListOf(Json.of(receiverSsrc))
        reply(
            msg,
            jsonObj {
                put("type", "ANSWER")
                put("seqNum", seqNum)
                put("result", "ok")
                put(
                    "answer",
                    jsonObj {
                        put("udpPort", udpPort)
                        put("sendIndexes", Json.arrayOfInts(sendIndexes))
                        put("ssrcs", Json.Arr(ssrcList))
                        put(
                            "constraints",
                            jsonObj {
                                put(
                                    "audio",
                                    jsonObj {
                                        put("maxSampleRate", 48000); put("maxChannels", 2)
                                        put("minBitRate", 32000); put("maxBitRate", 320000)
                                    },
                                )
                                put(
                                    "video",
                                    jsonObj {
                                        put(
                                            "maxDimensions",
                                            jsonObj { put("width", 1920); put("height", 1080); put("frameRate", "30") },
                                        )
                                        put("minBitRate", 300000)
                                        put("maxBitRate", 10000000)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }
}

/**
 * A frame source emitting [count] synthetic frames, then nothing.
 *
 * The frame id is dictated by the sender (see [FrameSource.nextFrame]); the source only owns the
 * presentation clock, so the RTP timestamp advances with a monotonic counter of its own.
 */
private class SyntheticFrameSource(
    private val count: Int,
    private val frameBytes: Int = 3000,
    private val frameIntervalMs: Long = 33,   // 30 fps
) : FrameSource {
    private val produced = AtomicInteger(0)
    @Volatile private var nextEmitAtMs = 0L

    /** How many times a key frame was requested. */
    val keyFrameRequests = AtomicInteger(0)

    /** Frames handed out so far. */
    fun producedCount(): Int = produced.get()

    override fun nextFrame(timeoutMs: Long, nextFrameId: Int): EncodedFrame? {
        // Emit at a realistic capture rate rather than as fast as the pump can spin, so that the
        // sender's in-flight budget behaves the way it would against a real encoder.
        val now = System.currentTimeMillis()
        if (now < nextEmitAtMs) {
            Thread.sleep(minOf(timeoutMs, nextEmitAtMs - now).coerceAtLeast(1))
            return null
        }
        nextEmitAtMs = now + frameIntervalMs
        val n = produced.incrementAndGet()
        if (n > count) return null
        val requested = keyFrameRequests.getAndSet(0) > 0
        val isKey = n == 1 || requested
        return EncodedFrame(
            frameId = nextFrameId,
            isKeyFrame = isKey,
            referencedFrameId = if (isKey) nextFrameId else nextFrameId - 1,
            // 30 fps at the 90 kHz video timebase: 3000 ticks per frame.
            rtpTimestamp = n.toLong() * 3000,
            data = ByteArray(frameBytes) { ((it + n) and 0xFF).toByte() },
            captureTimeMs = n.toLong() * 33,
        )
    }

    override fun requestKeyFrame() { keyFrameRequests.incrementAndGet() }
}

/** Builds a Cast feedback packet acknowledging every frame up to [checkpoint]. */
private fun castFeedback(receiverSsrc: Long, senderSsrc: Long, checkpoint: Int): ByteArray {
    val body = app.castmirror.core.util.ByteWriter(32)
    body.u32(receiverSsrc)
    body.u32(senderSsrc)
    body.u32(RtcpDefines.CAST_IDENTIFIER_WORD)
    body.u8(checkpoint and 0xFF)
    body.u8(0)
    body.u16(StreamingConstants.DEFAULT_TARGET_DELAY_MS)
    val payload = body.toByteArray()
    val out = app.castmirror.core.util.ByteWriter(payload.size + 4)
    app.castmirror.core.cast.streaming.rtcp.SenderReportBuilder.writeCommonHeader(
        out, RtcpDefines.SUBTYPE_FEEDBACK, RtcpDefines.PT_PAYLOAD_SPECIFIC, payload.size,
    )
    out.bytes(payload)
    return out.toByteArray()
}

/** Full bring-up against an in-memory fake receiver. */
fun runEndToEndTest() {
    T.section("End-to-end session bring-up")

    val toReceiver = PipedOutputStream()
    val receiverIn = PipedInputStream(toReceiver, 1 shl 16)
    val toSender = PipedOutputStream()
    val senderIn = PipedInputStream(toSender, 1 shl 16)

    // The default path: GMS's app id and OFFER dialect.
    val receiver = FakeReceiver(receiverIn, toSender, appId = CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO)
    receiver.start()

    val udp = FakeUdpTransport()
    val source = SyntheticFrameSource(count = 1000)
    val states = java.util.Collections.synchronizedList(ArrayList<SessionState>())

    val controller = SessionController(
        host = "192.168.1.50",
        port = 8009,
        config = SessionConfig(
            // Everything left at its default: the GMS ladder, the GMS OFFER dialect, video only.
            // The fake UDP transport never delivers RTCP, so disable the Android-TV retry probe.
            fallbackPolicy = NoPayloadTypeFallback,
        ),
        frameSourceProvider = { source },
        channelProvider = { _, _ ->
            CastChannel(
                PipeCastSocket(senderIn, toReceiver, "fake-receiver"),
                heartbeatIntervalMs = 0,
            )
        },
        udpProvider = { _, port ->
            T.assertEquals("UDP goes to the ANSWER's udpPort", 33533, port)
            udp
        },
    )
    controller.addListener(object : SessionListener {
        override fun onStateChanged(from: SessionState, to: SessionState) { states.add(to) }
    })

    try {
        controller.start()
        T.assertEquals("the session reaches STREAMING", SessionState.STREAMING, controller.state)
        T.assertEquals(
            "it passed through every state in order",
            listOf(
                SessionState.CONNECTING, SessionState.CONNECTED, SessionState.LAUNCHING,
                SessionState.NEGOTIATING, SessionState.STREAMING,
            ),
            states.toList(),
        )
        T.assertEquals(
            "the GMS app id was launched by default",
            CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO,
            controller.launchedAppId,
        )
        T.assertEquals(
            "the GMS OFFER dialect was used",
            OfferProfile.GMS_SYSTEM_MIRRORING,
            controller.activeProfile,
        )
        T.assertEquals("the transportId was captured", receiver.transportId, controller.transportId)
        T.assertEquals("the sessionId was captured", receiver.sessionId, controller.sessionId)
        T.assertEquals("the ANSWER's udpPort", 33533, controller.answer?.udpPort)
        T.assertEquals("one stream was negotiated", 1, controller.streams.size)
        T.assertEquals("the negotiated codec", "h264", controller.streams[0].codecName)
        T.assertEquals("the receiver SSRC came from the ANSWER", receiver.receiverSsrc, controller.streams[0].receiverSsrc)

        // Two virtual connections must have been opened: sender-0 -> receiver-0, and a distinct
        // per-session sender id -> the app's transportId. Missing the second is the classic bug.
        val connects = receiver.received.filter {
            it.namespace == Namespaces.CONNECTION &&
                Json.parse(it.payloadUtf8!!).asObjOrNull()?.get("type")?.asStringOrNull() == "CONNECT"
        }
        T.assertEquals("two CONNECTs were sent", 2, connects.size)
        T.assertEquals("the first CONNECT is to the platform receiver", Namespaces.PLATFORM_RECEIVER_ID, connects[0].destinationId)
        T.assertEquals("the second CONNECT is to the app's transportId", receiver.transportId, connects[1].destinationId)
        T.check("the second CONNECT uses a distinct sender id", connects[1].sourceId != Namespaces.PLATFORM_SENDER_ID)

        val offerMsg = receiver.received.first { it.namespace == Namespaces.WEBRTC }
        T.assertEquals("the OFFER was addressed to the transportId", receiver.transportId, offerMsg.destinationId)
        T.assertEquals(
            "the OFFER used the session sender id",
            connects[1].sourceId,
            offerMsg.sourceId,
        )
        T.assertEquals("one video stream was offered", 1, receiver.offeredStreams?.size)
        val offeredVideo = receiver.offeredStreams!![0].asObjOrNull()!!
        T.assertEquals("the offered stream is at index 0 with no audio", 0, offeredVideo["index"]?.asIntOrNull())
        T.assertEquals("GMS payload type 96", 96, offeredVideo["rtpPayloadType"]?.asIntOrNull())
        T.assertEquals("GMS SSRC 11", 11L, offeredVideo["ssrc"]?.asLongOrNull())
        T.assertEquals("GMS target delay", 100, offeredVideo["targetDelay"]?.asIntOrNull())
        T.assertEquals("GMS storeTime mirrors targetDelay", 100, offeredVideo["storeTime"]?.asIntOrNull())
        T.assertEquals(
            "GMS rtpExtensions token",
            "adaptive_playout_delay",
            offeredVideo["rtpExtensions"]?.asArrOrNull()?.get(0)?.asStringOrNull(),
        )
        T.assertEquals("the OFFER seqNum starts at 1", 1, receiver.offerSeqNum)
        T.assertEquals("offer.udpPort is the GMS constant", 2346, receiver.offerUdpPort)
        // Google's channel adapter stamps the Cast v2 requestId onto the payload, using the same
        // integer as the mirroring seqNum.
        T.assertEquals("the OFFER carries a requestId equal to its seqNum", 1, receiver.offerRequestId)

        // Wait for media to flow.
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && udp.sentCount < 8) Thread.sleep(10)

        val sent = udp.sent
        T.check("packets reached the UDP transport", sent.isNotEmpty(), "sent ${sent.size}")

        // A Sender Report must precede the first RTP packet: without the lip-sync anchor the
        // receiver cannot time playout, and cannot produce a Receiver Report.
        T.assertEquals("the first packet is RTCP", 0x80, Bytes.u8(sent[0], 0))
        T.assertEquals("the first packet is a Sender Report", RtcpDefines.PT_SENDER_REPORT, Bytes.u8(sent[0], 1))
        T.assertEquals(
            "the Sender Report carries the sender SSRC",
            controller.streams[0].offerStream.common.ssrc,
            Bytes.u32(sent[0], 4),
        )

        val rtp = sent.drop(1).filter { Bytes.u8(it, 1) and 0x7F == 96 }
        T.check("RTP packets were sent", rtp.isNotEmpty(), "got ${rtp.size}")
        T.assertEquals("RTP byte 0 is always 0x80", listOf(0x80), rtp.map { Bytes.u8(it, 0) }.distinct())
        T.assertEquals(
            "RTP packets carry the sender SSRC",
            listOf(controller.streams[0].offerStream.common.ssrc),
            rtp.map { Bytes.u32(it, 8) }.distinct(),
        )
        T.check(
            "the reference-frame-id bit is always set",
            rtp.all { (Bytes.u8(it, 12) and 0x40) != 0 },
        )

        // 3000-byte frames at a 1449-byte chunk size are 3 packets each, ids 0..2.
        val firstFrame = rtp.filter { Bytes.u8(it, 13) == FrameIds.FIRST }
        // Distinct, because with no feedback arriving the sender also kickstarts by resending the
        // last packet of the last-enqueued frame.
        T.assertEquals(
            "frame 0 was sent as three packets",
            listOf(0, 1, 2),
            firstFrame.map { Bytes.u16(it, 14) }.distinct().sorted(),
        )
        T.check("frame 0 is flagged as a key frame", firstFrame.all { (Bytes.u8(it, 12) and 0x80) != 0 })
        T.assertEquals("frame 0 references itself", listOf(0), firstFrame.map { Bytes.u8(it, 18) }.distinct())
        T.assertEquals("max packet id is 2", listOf(2), firstFrame.map { Bytes.u16(it, 16) }.distinct())
        T.check(
            "only the last packet of frame 0 carries the marker bit",
            firstFrame.filter { (Bytes.u8(it, 1) and 0x80) != 0 }.all { Bytes.u16(it, 14) == 2 },
        )
        T.check(
            "every packet of a frame gets a unique sequence number",
            firstFrame.map { Bytes.u16(it, 2) }.distinct().size == firstFrame.size,
        )

        val frameIds = rtp.map { Bytes.u8(it, 13) }.distinct()
        T.check("frame ids appear in ascending order", frameIds == frameIds.sorted(), "$frameIds")
        T.check("frame ids start at FrameId::first() == 0", frameIds.first() == FrameIds.FIRST, "$frameIds")

        val deltas = rtp.filter { Bytes.u8(it, 13) == 1 }
        if (deltas.isNotEmpty()) {
            T.check("frame 1 is a delta frame", deltas.all { (Bytes.u8(it, 12) and 0x80) == 0 })
            T.assertEquals("frame 1 references frame 0", listOf(0), deltas.map { Bytes.u8(it, 18) }.distinct())
        } else {
            T.check("frame 1 was sent", false, "no packets for frame 1 among $frameIds")
        }

        // RTP timestamps are 3000 ticks apart (30 fps at 90 kHz) and identical within a frame.
        T.assertEquals("frame 0 RTP timestamp", 3000L, Bytes.u32(firstFrame[0], 4))
        T.assertEquals(
            "all packets of a frame share one RTP timestamp",
            1,
            firstFrame.map { Bytes.u32(it, 4) }.distinct().size,
        )

        val stats = controller.streams[0].sender.stats()
        T.check("the sender counted its packets", stats.packetsSent >= rtp.size.toLong())

        // With no RTCP coming back the checkpoint never moves, so the in-flight-media-duration
        // budget throttles the sender. Crucially it does this by BACKPRESSURE, not by shedding
        // encoded frames: video is an H.264 reference chain and a dropped encoded frame datamoshes
        // the receiver. So the pump holds frames (bounded in-flight) and framesDropped stays 0 —
        // the datamosh fix. Let it run a while, then assert the invariants.
        T.assertEquals("the checkpoint is still at the leader without feedback", FrameIds.LEADER, stats.checkpointFrameId)
        Thread.sleep(1500)
        val throttled = controller.streams[0].sender.stats()
        T.assertEquals(
            "no encoded frame is ever shed (chain preserved) even with no feedback",
            0L,
            throttled.framesDropped,
        )
        T.check(
            "in-flight is bounded by backpressure, not unbounded buffering",
            (throttled.lastEnqueuedFrameId - throttled.checkpointFrameId) <= StreamingConstants.MAX_UNACKED_FRAMES,
            "inflight ${throttled.lastEnqueuedFrameId - throttled.checkpointFrameId}",
        )

        // Now acknowledge what has been sent, and the pipeline must start moving again.
        val senderSsrc = controller.streams[0].offerStream.common.ssrc
        val receiverSsrc = controller.streams[0].receiverSsrc
        val before = controller.streams[0].sender.stats()
        repeat(6) {
            val s = controller.streams[0].sender.stats()
            udp.deliver(castFeedback(receiverSsrc, senderSsrc, s.lastEnqueuedFrameId))
            Thread.sleep(120)
        }
        val after = controller.streams[0].sender.stats()
        T.check(
            "acknowledging frames advances the checkpoint",
            after.checkpointFrameId > FrameIds.LEADER,
            "checkpoint ${after.checkpointFrameId}",
        )
        T.check(
            "acknowledging frames lets more media flow",
            after.framesEnqueued > before.framesEnqueued,
            "${before.framesEnqueued} -> ${after.framesEnqueued}",
        )
        T.check(
            "RTCP feedback for the wrong SSRC pair is ignored",
            run {
                val bogus = controller.streams[0].sender.handleRtcpPacket(
                    castFeedback(receiverSsrc + 999, senderSsrc, 1)
                )
                bogus?.checkpointFrameId == null
            },
        )
    } finally {
        controller.stop()
        receiver.stop()
    }

    T.assertEquals("the session terminates cleanly", SessionState.TERMINATED, controller.state)

    fun sawStop(): Boolean = receiver.received.toList().any {
        it.namespace == Namespaces.RECEIVER &&
            Json.parse(it.payloadUtf8!!).asObjOrNull()?.get("type")?.asStringOrNull() == "STOP"
    }
    val stopDeadline = System.currentTimeMillis() + 1000
    while (System.currentTimeMillis() < stopDeadline && !sawStop()) Thread.sleep(10)
    T.check("STOP was sent with the sessionId", sawStop())

    T.section("End-to-end: launch fallback")

    // A receiver that only supports the Android mirroring app id must be reached by falling down
    // the launch ladder.
    val toReceiver2 = PipedOutputStream()
    val receiverIn2 = PipedInputStream(toReceiver2, 1 shl 16)
    val toSender2 = PipedOutputStream()
    val senderIn2 = PipedInputStream(toSender2, 1 shl 16)
    // This receiver only knows the Chrome app id, so rung 1 must fail and rung 2 must succeed.
    val receiver2 = FakeReceiver(receiverIn2, toSender2, appId = CastAppIds.CAST_STREAMING_AUDIO_VIDEO)
    receiver2.start()

    val controller2 = SessionController(
        host = "192.168.1.51",
        config = SessionConfig(
            probeAppAvailability = false,   // force the LAUNCH_ERROR path rather than the probe
            fallbackPolicy = NoPayloadTypeFallback,
        ),
        frameSourceProvider = { SyntheticFrameSource(count = 100) },
        channelProvider = { _, _ ->
            CastChannel(PipeCastSocket(senderIn2, toReceiver2, "fake-2"), heartbeatIntervalMs = 0)
        },
        udpProvider = { _, _ -> FakeUdpTransport() },
    )
    try {
        controller2.start()
        T.assertEquals(
            "the launch ladder fell through to the Chrome app id",
            CastAppIds.CAST_STREAMING_AUDIO_VIDEO,
            controller2.launchedAppId,
        )
        T.assertEquals(
            "the fallback rung switched to the openscreen dialect",
            OfferProfile.OPENSCREEN_CHROME,
            controller2.activeProfile,
        )
        T.assertEquals("the fallback session also streams", SessionState.STREAMING, controller2.state)
        val fallbackStream = receiver2.offeredStreams!![0].asObjOrNull()!!
        T.assertEquals(
            "the fallback OFFER uses canonical payload type 101 for h264",
            101,
            fallbackStream["rtpPayloadType"]?.asIntOrNull(),
        )
        T.check(
            "the fallback OFFER emits codecParameter as openscreen does",
            fallbackStream.has("codecParameter"),
        )
        T.check("the fallback OFFER omits storeTime", !fallbackStream.has("storeTime"))
        // The openscreen dialect gets the same requestId stamp: SenderMessage::Parse reads only
        // seqNum/type/offer by name, so the extra key is ignored rather than rejected.
        T.assertEquals(
            "the openscreen-dialect OFFER also carries requestId == seqNum",
            receiver2.offerSeqNum,
            receiver2.offerRequestId,
        )
        T.check("and that seqNum is a large random start, as Chrome uses", receiver2.offerSeqNum > 1)
    } finally {
        controller2.stop()
        receiver2.stop()
    }

    T.section("End-to-end: LAUNCH answered before the app registers")

    // Regression: the RECEIVER_STATUS that answers LAUNCH does not always name the app yet. The
    // controller must poll GET_STATUS rather than declaring the rung failed, which would STOP a
    // perfectly good session and launch a different app id on top of it.
    val toReceiver3 = PipedOutputStream()
    val receiverIn3 = PipedInputStream(toReceiver3, 1 shl 16)
    val toSender3 = PipedOutputStream()
    val senderIn3 = PipedInputStream(toSender3, 1 shl 16)
    val receiver3 = FakeReceiver(
        receiverIn3,
        toSender3,
        appId = CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO,
        statusesWithoutApp = 2,
    )
    receiver3.start()

    val controller3 = SessionController(
        host = "192.168.1.52",
        config = SessionConfig(fallbackPolicy = NoPayloadTypeFallback),
        frameSourceProvider = { SyntheticFrameSource(count = 100) },
        channelProvider = { _, _ ->
            CastChannel(PipeCastSocket(senderIn3, toReceiver3, "fake-3"), heartbeatIntervalMs = 0)
        },
        udpProvider = { _, _ -> FakeUdpTransport() },
    )
    try {
        controller3.start()
        T.assertEquals(
            "a late transportId does not knock the session down the ladder",
            CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO,
            controller3.launchedAppId,
        )
        T.assertEquals("the transportId was eventually captured", receiver3.transportId, controller3.transportId)
        T.assertEquals("the session still reaches STREAMING", SessionState.STREAMING, controller3.state)
        // Exactly one LAUNCH: a second would mean the ladder advanced and stopped the first app.
        val launches = receiver3.received.toList().count {
            it.namespace == Namespaces.RECEIVER &&
                Json.parse(it.payloadUtf8!!).asObjOrNull()?.get("type")?.asStringOrNull() == "LAUNCH"
        }
        T.assertEquals("only one LAUNCH was sent", 1, launches)
    } finally {
        controller3.stop()
        receiver3.stop()
    }

    T.section("End-to-end: the receiver answers but never sends RTCP")

    // Regression: a receiver that ANSWERs and then stays silent on UDP is not receiving media at
    // all (AP client isolation, a firewalled UDP path, a payload type its decoder rejected). The
    // session must fail with a diagnosable error instead of sitting in STREAMING behind a black
    // screen until the user gives up.
    val toReceiver4 = PipedOutputStream()
    val receiverIn4 = PipedInputStream(toReceiver4, 1 shl 16)
    val toSender4 = PipedOutputStream()
    val senderIn4 = PipedInputStream(toSender4, 1 shl 16)
    val receiver4 = FakeReceiver(receiverIn4, toSender4, appId = CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO)
    receiver4.start()

    val controller4 = SessionController(
        host = "192.168.1.53",
        config = SessionConfig(
            fallbackPolicy = NoPayloadTypeFallback,
            mediaTimeoutMs = 400,
        ),
        frameSourceProvider = { SyntheticFrameSource(count = 10_000) },
        channelProvider = { _, _ ->
            CastChannel(PipeCastSocket(senderIn4, toReceiver4, "fake-4"), heartbeatIntervalMs = 0)
        },
        // Never delivers a datagram, so no RTCP ever arrives.
        udpProvider = { _, _ -> FakeUdpTransport() },
    )
    try {
        controller4.start()
        T.assertEquals("the silent-RTCP session still reaches STREAMING first", SessionState.STREAMING, controller4.state)
        val timeoutDeadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < timeoutDeadline &&
            controller4.state != SessionState.FAILED &&
            controller4.state != SessionState.TERMINATED
        ) {
            Thread.sleep(20)
        }
        T.assertEquals("a silent media path fails the session", SessionState.FAILED, controller4.state)
        T.check(
            "the failure names the missing RTCP",
            controller4.failure?.message?.contains("no RTCP") == true,
            "${controller4.failure?.message}",
        )
        // Regression: stop() runs on the pump thread here. teardownMediaPath() used to interrupt
        // every pump thread including the caller, so the next blocking write threw
        // InterruptedException and the teardown was abandoned before STOP was ever queued --
        // leaving the receiver's app running and the session stuck in STOPPING.
        val stopDeadline4 = System.currentTimeMillis() + 2000
        fun sawStop4(): Boolean = receiver4.received.toList().any {
            it.namespace == Namespaces.RECEIVER &&
                Json.parse(it.payloadUtf8!!).asObjOrNull()?.get("type")?.asStringOrNull() == "STOP"
        }
        while (System.currentTimeMillis() < stopDeadline4 && !sawStop4()) Thread.sleep(10)
        T.check("teardown from the pump thread still sends STOP to the receiver", sawStop4())
    } finally {
        controller4.stop()
        receiver4.stop()
    }

    T.section("Heartbeat: answering an inbound PING")

    // Receivers ping senders, and a channel that does not PONG is reaped. The reply must come
    // from a concrete endpoint: a PING addressed to the broadcast wildcard must NOT be answered
    // with source_id "*", which no virtual connection can match.
    fun pongSourceFor(pingDestination: String): String {
        val toChannel = PipedOutputStream()
        val channelIn = PipedInputStream(toChannel, 1 shl 16)
        val fromChannel = PipedOutputStream()
        val peerIn = PipedInputStream(fromChannel, 1 shl 16)
        val channel = CastChannel(
            PipeCastSocket(channelIn, fromChannel, "ping-test"),
            heartbeatIntervalMs = 0,
        )
        try {
            channel.start()
            toChannel.write(
                CastMessage(
                    sourceId = "Tr@n\u0024p0rt-0",
                    destinationId = pingDestination,
                    namespace = Namespaces.HEARTBEAT,
                    payloadUtf8 = jsonObj { put("type", "PING") }.encode(),
                ).encodeFramed()
            )
            toChannel.flush()
            val framer = CastMessageFramer()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                val n = peerIn.read(buf)
                if (n <= 0) break
                framer.append(buf, 0, n)
                while (true) {
                    val m = framer.next() ?: break
                    if (m.namespace == Namespaces.HEARTBEAT) return m.sourceId
                }
            }
            return "<no PONG>"
        } finally {
            channel.close()
        }
    }

    T.assertEquals(
        "a PING addressed to sender-0 is answered as sender-0",
        Namespaces.PLATFORM_SENDER_ID,
        pongSourceFor(Namespaces.PLATFORM_SENDER_ID),
    )
    T.assertEquals(
        "a broadcast PING is answered from a concrete endpoint, never \"*\"",
        Namespaces.PLATFORM_SENDER_ID,
        pongSourceFor(Namespaces.BROADCAST_ID),
    )
}

/**
 * End-to-end proof that the receiver's field-7 STRING payloads now correlate.
 *
 * Reproduces the exact device failure at the channel level: a real Chromecast puts JSON in
 * protobuf field 7 with payload_type=STRING. Before the decoder became payload_type-driven,
 * `payloadUtf8` came back null, `dispatch()` skipped requestId correlation, and `getStatus()`
 * timed out even though the reply had arrived. This feeds the actual captured RECEIVER_STATUS
 * bytes into a live [CastChannel] and asserts the blocking request completes.
 */
fun runFieldSevenCorrelationTest() {
    T.section("Field-7 STRING payloads correlate end-to-end")

    // Pipes: senderIn is what the channel reads; toSender is where the "receiver" writes frames.
    val toSender = PipedOutputStream()
    val senderIn = PipedInputStream(toSender, 1 shl 16)
    val toReceiver = PipedOutputStream()
    val receiverIn = PipedInputStream(toReceiver, 1 shl 16)

    // Drain whatever the channel sends (CONNECT, GET_STATUS) so its writer never blocks.
    val drain = Thread {
        val buf = ByteArray(4096)
        try { while (receiverIn.read(buf) >= 0) { /* discard */ } } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }

    val channel = CastChannel(
        PipeCastSocket(senderIn, toReceiver, "field7-test"),
        heartbeatIntervalMs = 0,
    )

    val broadcasts = java.util.concurrent.CopyOnWriteArrayList<ReceiverStatus>()
    channel.addListener(object : app.castmirror.core.cast.channel.CastChannelListener {
        override fun onReceiverStatus(status: ReceiverStatus) { broadcasts.add(status) }
    })

    fun frame(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        Bytes.putU32(out, 0, body.size.toLong())
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    try {
        channel.start()
        channel.openVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)

        // getStatus() blocks on requestId 1 (nextRequestId starts at 1). Run it on a thread.
        val result = java.util.concurrent.atomic.AtomicReference<ReceiverStatus?>(null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val caller = Thread {
            try { result.set(channel.getStatus(timeoutMs = 4000)) }
            catch (e: Throwable) { failure.set(e) }
        }.apply { isDaemon = true; start() }

        // Give the request a moment to register its pending slot.
        Thread.sleep(100)

        // First feed the field-7 BROADCAST (requestId 0, dst "*"): it must be dispatched as an
        // unsolicited status but must NOT complete the pending requestId-1 getStatus().
        val broadcastBody = field7StringCastFrameBody(
            sourceId = "receiver-0",
            destinationId = "*",
            namespace = Namespaces.RECEIVER,
            json = """{"requestId":0,"type":"RECEIVER_STATUS","status":{"applications":[]}}""",
        )
        toSender.write(frame(broadcastBody)); toSender.flush()

        // Wait for the broadcast to be dispatched, then confirm getStatus is still blocked.
        val bDeadline = System.currentTimeMillis() + 1000
        while (broadcasts.isEmpty() && System.currentTimeMillis() < bDeadline) Thread.sleep(10)
        T.check("the field-7 broadcast was dispatched as a RECEIVER_STATUS", broadcasts.isNotEmpty())
        T.check("a requestId-0 broadcast does not complete the pending request", result.get() == null && failure.get() == null)

        // Now feed the captured field-7 RECEIVER_STATUS REPLY that echoes requestId 1.
        val replyBody = Bytes.fromHex(
            "0803120a72656365697665722d301a0873656e6465722d30222375726e3a782d636173743a636f6d2e676f" +
                "6f676c652e636173742e726563656976657228003aa3017b22726571756573744964223a312c2273746174" +
                "7573223a7b22757365724571223a7b7d2c22766f6c756d65223a7b22636f6e74726f6c54797065223a2261" +
                "7474656e756174696f6e222c226c6576656c223a312e302c226d75746564223a66616c73652c2273746570" +
                "496e74657276616c223a302e30353030303030303037343530353830367d7d2c2274797065223a22524543" +
                "45495645525f535441545553227d"
        )
        toSender.write(frame(replyBody)); toSender.flush()

        caller.join(3000)
        T.check("getStatus() did not throw", failure.get() == null, "${failure.get()}")
        T.check("getStatus() returned a ReceiverStatus once the field-7 reply arrived", result.get() != null)
    } finally {
        channel.close()
        drain.interrupt()
    }
}

/**
 * A synthetic AUDIO frame source: independent access units, as the audio contract requires.
 *
 * Each frame is `isKeyFrame = true` with `referencedFrameId == frameId` (self), an RTP timestamp
 * advancing by one AAC-LC access unit (1024 ticks at 48 kHz), and deterministic bytes so a test can
 * verify the on-wire ciphertext decrypts back to them.
 */
private class AudioSyntheticFrameSource(
    private val count: Int = 1000,
    val frameBytes: Int = 200,
    private val ticksPerFrame: Long = 1024,
    private val slowMs: Long = 0,
    private val failImmediately: Boolean = false,
) : FrameSource {
    private val produced = AtomicInteger(0)
    val closed = AtomicBoolean(false)

    /** The deterministic payload an access unit with [frameId] carries, before encryption. */
    fun plaintextFor(frameId: Int): ByteArray = ByteArray(frameBytes) { ((it + frameId) and 0xFF).toByte() }

    override fun nextFrame(timeoutMs: Long, nextFrameId: Int): EncodedFrame? {
        if (failImmediately) throw RuntimeException("audio capture failed")
        if (slowMs > 0) { Thread.sleep(minOf(timeoutMs, slowMs).coerceAtLeast(1)); return null }
        val n = produced.incrementAndGet()
        if (n > count) { Thread.sleep(minOf(timeoutMs, 5).coerceAtLeast(1)); return null }
        return EncodedFrame(
            frameId = nextFrameId,
            isKeyFrame = true,
            referencedFrameId = nextFrameId,
            rtpTimestamp = nextFrameId.toLong() * ticksPerFrame,
            data = plaintextFor(nextFrameId),
            captureTimeMs = nextFrameId.toLong() * 21,
        )
    }

    override fun close() { closed.set(true) }
}

/** Audio+video: the second stream negotiates, flows encrypted on its own SSRC/PT, and is decoupled. */
fun runAudioVideoTest() {
    T.section("Audio+video: the audio stream negotiates and flows")

    val toReceiver = PipedOutputStream()
    val receiverIn = PipedInputStream(toReceiver, 1 shl 16)
    val toSender = PipedOutputStream()
    val senderIn = PipedInputStream(toSender, 1 shl 16)

    val receiver = FakeReceiver(
        receiverIn, toSender,
        appId = CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO,
        acceptAudio = true,
    )
    receiver.start()

    val udp = FakeUdpTransport()
    val video = SyntheticFrameSource(count = 1000)
    val audio = AudioSyntheticFrameSource(count = 1000)

    val controller = SessionController(
        host = "192.168.1.60",
        port = 8009,
        config = SessionConfig(
            // GMS dialect, and now offering AAC audio alongside H.264 video.
            audioCodecs = listOf("aac"),
            fallbackPolicy = NoPayloadTypeFallback,
        ),
        frameSourceProvider = { ns -> if (ns.isAudio) audio else video },
        channelProvider = { _, _ -> CastChannel(PipeCastSocket(senderIn, toReceiver, "av"), heartbeatIntervalMs = 0) },
        udpProvider = { _, _ -> udp },
    )

    try {
        controller.start()
        T.assertEquals("the session reaches STREAMING", SessionState.STREAMING, controller.state)

        // Negotiation: both streams accepted, audio at OFFER index 0.
        T.assertEquals("two streams negotiated (audio + video)", 2, controller.streams.size)
        val audioStream = controller.streams.firstOrNull { it.isAudio }
        val videoStream = controller.streams.firstOrNull { !it.isAudio }
        T.check("an audio stream was negotiated", audioStream != null)
        T.check("a video stream was negotiated", videoStream != null)
        T.assertEquals("audio is OFFER index 0", 0, audioStream!!.index)
        T.assertEquals("the ANSWER's sendIndexes include the audio index", true, receiver.audioIndex == 0)
        T.assertEquals("audio uses the GMS fixed audio SSRC 1", 1L, audioStream.offerStream.common.ssrc)
        T.assertEquals("audio uses payload type 127 (the Android-TV audio hack)", 127, audioStream.offerStream.common.rtpPayloadType)
        T.assertEquals("audio timebase is the 48 kHz sample rate", 48_000, audioStream.rtpTimebase)
        T.assertEquals("audio Sender is flagged isAudio", true, audioStream.sender.isAudio)
        T.assertEquals("audio receiver SSRC came from the ANSWER", receiver.audioReceiverSsrc, audioStream.receiverSsrc)

        // Let media flow.
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && udp.sentCount < 12) Thread.sleep(10)
        val sent = udp.sent

        // Audio RTP: SSRC 1 at offset 8, PT 127 in byte 1 (low 7 bits).
        val audioRtp = sent.filter { it.size >= 19 && Bytes.u8(it, 1) and 0x7F == 127 }
        T.check("audio RTP packets were sent", audioRtp.isNotEmpty(), "got ${audioRtp.size}")
        T.assertEquals("audio RTP carries the audio SSRC 1", listOf(1L), audioRtp.map { Bytes.u32(it, 8) }.distinct())
        T.check("audio RTP carries PT 127", audioRtp.all { Bytes.u8(it, 1) and 0x7F == 127 })
        // Each audio access unit is independent: the key-frame bit is set and it references itself.
        T.check("every audio frame sets the key-frame bit", audioRtp.all { (Bytes.u8(it, 12) and 0x80) != 0 })
        T.check("every audio frame references itself", audioRtp.all { Bytes.u8(it, 13) == Bytes.u8(it, 18) })

        // Video RTP is on its own SSRC (11) and PT (96), demuxed correctly on the shared socket.
        val videoRtp = sent.filter { it.size >= 19 && Bytes.u8(it, 1) and 0x7F == 96 }
        T.check("video RTP still flows on its own SSRC/PT", videoRtp.isNotEmpty())
        T.assertEquals("video RTP carries the video SSRC 11", listOf(11L), videoRtp.map { Bytes.u32(it, 8) }.distinct())

        // AES-CTR encrypts audio exactly as video: the on-wire ciphertext of a single-packet audio
        // frame decrypts back to the source's known plaintext, using the audio stream's own key/IV.
        val common = audioStream.offerStream.common
        val crypto = FrameCrypto(common.aesKey, common.aesIvMask)
        val onePacket = audioRtp.first { Bytes.u16(it, 16) == 0 } // max_packet_id == 0: a whole frame in one packet
        val frameIdLow = Bytes.u8(onePacket, 13)
        val ciphertext = onePacket.copyOfRange(RtpDefines.BASE_RTP_HEADER_SIZE, onePacket.size)
        T.assertBytes(
            "audio payload decrypts to the source plaintext (AES-CTR applied to audio as to video)",
            audio.plaintextFor(frameIdLow),
            crypto.decrypt(frameIdLow, ciphertext),
        )

        // A Sender Report is emitted for the audio SSRC (its own lip-sync anchor).
        val audioSr = sent.firstOrNull { Bytes.u8(it, 1) == 200 && Bytes.u32(it, 4) == 1L }
        T.check("a Sender Report was emitted for the audio SSRC", audioSr != null)

        // Decoupling: audio and video ran to STREAMING together on independent pump threads.
        T.check("both streams enqueued frames", audioStream.sender.stats().framesEnqueued > 0 && videoStream!!.sender.stats().framesEnqueued > 0)
    } finally {
        controller.stop()
        receiver.stop()
    }

    T.section("Audio+video: a failing audio source never stalls the video")

    val toReceiver2 = PipedOutputStream()
    val receiverIn2 = PipedInputStream(toReceiver2, 1 shl 16)
    val toSender2 = PipedOutputStream()
    val senderIn2 = PipedInputStream(toSender2, 1 shl 16)
    val receiver2 = FakeReceiver(
        receiverIn2, toSender2,
        appId = CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO,
        acceptAudio = true,
    )
    receiver2.start()

    val udp2 = FakeUdpTransport()
    val video2 = SyntheticFrameSource(count = 1000)
    // This audio source throws on its very first nextFrame — Tier A capture can fail at any moment.
    val failingAudio = AudioSyntheticFrameSource(failImmediately = true)

    val controller2 = SessionController(
        host = "192.168.1.61",
        config = SessionConfig(audioCodecs = listOf("aac"), fallbackPolicy = NoPayloadTypeFallback),
        frameSourceProvider = { ns -> if (ns.isAudio) failingAudio else video2 },
        channelProvider = { _, _ -> CastChannel(PipeCastSocket(senderIn2, toReceiver2, "av2"), heartbeatIntervalMs = 0) },
        udpProvider = { _, _ -> udp2 },
    )
    try {
        controller2.start()
        T.assertEquals("the session still reaches STREAMING with a broken audio source", SessionState.STREAMING, controller2.state)
        val videoStream2 = controller2.streams.first { !it.isAudio }
        // Give video time to flow while the audio pump has already died.
        val vDeadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < vDeadline && videoStream2.sender.stats().framesEnqueued < 3) Thread.sleep(20)
        T.check(
            "video keeps flowing after the audio source failed",
            videoStream2.sender.stats().framesEnqueued >= 3,
            "video frames ${videoStream2.sender.stats().framesEnqueued}",
        )
        T.check("the failed audio source was closed", failingAudio.closed.get())
        T.assertEquals("the session is not torn down by the audio failure", SessionState.STREAMING, controller2.state)
        T.assertEquals("the audio stream enqueued nothing", 0L, controller2.streams.first { it.isAudio }.sender.stats().framesEnqueued)
    } finally {
        controller2.stop()
        receiver2.stop()
    }
}
