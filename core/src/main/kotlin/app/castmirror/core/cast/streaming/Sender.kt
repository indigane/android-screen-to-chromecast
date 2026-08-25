package app.castmirror.core.cast.streaming

import app.castmirror.core.StreamingConstants
import app.castmirror.core.cast.streaming.crypto.FrameCrypto
import app.castmirror.core.cast.streaming.rtcp.CompoundRtcpParser
import app.castmirror.core.cast.streaming.rtcp.NtpTime
import app.castmirror.core.cast.streaming.rtcp.RtcpFeedback
import app.castmirror.core.cast.streaming.rtcp.RtcpParseResult
import app.castmirror.core.cast.streaming.rtcp.SenderReportBuilder
import app.castmirror.core.cast.streaming.rtp.RtpDefines
import app.castmirror.core.cast.streaming.rtp.RtpPacketizer

/** Why [Sender.enqueue] refused a frame. */
enum class EnqueueResult {
    /** The frame was accepted and its packets are queued for transmission. */
    OK,

    /**
     * `frameId - checkpointFrameId` would exceed [StreamingConstants.MAX_UNACKED_FRAMES].
     * The frame is dropped — shedding frames early is the sender's congestion response.
     */
    REACHED_ID_SPAN_LIMIT,

    /** More media would be in flight than the current RTT/playout-delay budget allows. */
    MAX_DURATION_IN_FLIGHT,

    /** The encrypted frame needs more than 65535 packets. */
    PAYLOAD_TOO_LARGE,
}

/** Cumulative counters for one stream. */
data class SenderStats(
    /** RTP packets handed to the transport, retransmissions included. */
    val packetsSent: Long,
    /** Approximate payload octets sent, excluding RTP headers. */
    val octetsSent: Long,
    /** Frames accepted by [Sender.enqueue]. */
    val framesEnqueued: Long,
    /** Frames refused because of the id-span or in-flight-duration limits. */
    val framesDropped: Long,
    /** Packets re-flagged for send because of a NACK. */
    val packetsRetransmitted: Long,
    /** Kickstart packets sent. */
    val kickstartsSent: Long,
    /** Current smoothed round-trip time estimate in milliseconds; 0 means "not yet measured". */
    val roundTripTimeMs: Long,
    /** Highest frame id the receiver has confirmed receiving in full. */
    val checkpointFrameId: Int,
    /** Highest frame id handed to [Sender.enqueue]. */
    val lastEnqueuedFrameId: Int,
    /**
     * The receiver's most recent RTCP "fraction lost" numerator (over 256), or 0 if no Receiver
     * Report has arrived. This is the **real** congestion signal — a genuine measure of packets the
     * receiver did not get — as opposed to [framesDropped], which is a purely sender-side pacing
     * decision. Drive bitrate adaptation from this, never from [framesDropped]/[kickstartsSent].
     */
    val fractionLostNumerator: Int,
)

/** Optional observer for interesting sender-side events. */
interface SenderObserver {
    /** The receiver asked for a key frame (PLI), and the request was accepted. */
    fun onPictureLost() {}

    /** [count] packets were flagged for retransmission. */
    fun onPacketsRetransmitted(count: Int) {}

    /** [frameId] is fully received (or given up on) and its buffer has been released. */
    fun onFrameCanceled(frameId: Int) {}
}

/**
 * The per-stream Cast Streaming sender: encryption, packetization, retransmission and RTCP.
 *
 * Ported from `openscreen:cast/streaming/impl/sender_impl.cc`. One instance per SSRC — audio and
 * video are entirely independent, with their own frame-id, sequence-number and key spaces.
 *
 * This class holds **no threads**. [SenderPacketRouter] drives it: it asks for the next RTP or
 * RTCP packet to send and feeds it inbound RTCP. All public methods are synchronized on the
 * instance, so the router's send and receive threads may both call in.
 *
 * ### Reliability model
 *
 * The receiver reports a monotonically non-decreasing **checkpoint** frame id ("everything up to
 * and including this arrived"), optional out-of-order frame ACKs, and per-packet **NACKs**.
 * Retransmission is filtered by an RTT-based staleness test — without it, a NACK issued while
 * the packet was still in flight triggers a redundant retransmit, which causes congestion, which
 * causes more NACKs. When the newest frames are lost *entirely* the receiver cannot NACK what it
 * does not know exists, so the sender periodically **kickstarts** by resending the last packet of
 * the last-enqueued frame (every packet advertises the frame's total packet count).
 *
 * @param senderSsrc this stream's SSRC, as offered.
 * @param receiverSsrc the receiver's SSRC for this stream, from the ANSWER. Must differ from
 *   [senderSsrc].
 * @param rtpPayloadType the payload type; must match the OFFER.
 * @param rtpTimebase 90000 for video, the sample rate for audio.
 * @param aesKey the 16-byte key offered for this stream.
 * @param aesIvMask the 16-byte IV mask offered for this stream.
 * @param targetPlayoutDelayMs the offered `targetDelay`.
 * @param maxPacketSize on-the-wire packet ceiling.
 * @param isAudio whether this is the audio stream; audio is paced ahead of video.
 * @param clock monotonic millisecond clock, injectable for tests.
 * @param wallClock wall-clock millisecond source. Read **once**, at construction, to fix the NTP
 *   origin; every subsequent NTP timestamp is [clock] advanced from there, as
 *   `NtpTimeConverter` does.
 */
class Sender(
    val senderSsrc: Long,
    val receiverSsrc: Long,
    val rtpPayloadType: Int,
    val rtpTimebase: Int,
    aesKey: ByteArray,
    aesIvMask: ByteArray,
    val targetPlayoutDelayMs: Int = StreamingConstants.DEFAULT_TARGET_DELAY_MS,
    maxPacketSize: Int = StreamingConstants.MAX_RTP_PACKET_SIZE_IPV4,
    val isAudio: Boolean = false,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    initialSequenceNumber: Int = RtpPacketizer.randomSequenceNumberStart(),
) {
    init {
        require(senderSsrc != receiverSsrc) { "sender and receiver SSRCs must differ" }
    }

    private val crypto = FrameCrypto(aesKey, aesIvMask)
    private val packetizer = RtpPacketizer(rtpPayloadType, senderSsrc, maxPacketSize, initialSequenceNumber)
    private val reportBuilder = SenderReportBuilder(senderSsrc)
    private val rtcpParser = CompoundRtcpParser(senderSsrc, receiverSsrc)

    /** In-flight frames, keyed by full 32-bit frame id. */
    private val slots = LinkedHashMap<Int, FrameSlot>()

    // All five frame-id trackers start at the "leader" (-1), exactly as `sender_impl.h:202-241`
    // initialises them. That is what makes `nextFrameId()` return FrameId::first() == 0 for the
    // very first frame, which is what openscreen's receiver waits for before it will play
    // anything (`receiver_impl.cc:162`, with allow_skip_to_keyframe defaulting to false).
    private var checkpointFrameId = FrameIds.LEADER
    private var lastEnqueuedFrameId = FrameIds.LEADER
    private var lastEnqueuedKeyFrameId = FrameIds.LEADER
    private var lastEnqueuedKeyFrameTimeMs = NEVER
    private var pictureLostAtFrameId = FrameIds.LEADER
    private var latestExpectedFrameId = FrameIds.LEADER

    private var roundTripTimeMs = 0L
    private var rtcpArrivalTimeMs = NEVER
    private var latestFractionLostNumerator = 0

    /** Anchor for the sender report's `(NTP, RTP)` lip-sync pair. */
    private var anchorRtpTimestamp = 0L
    private var anchorTimeMs = NEVER

    private var packetsSent = 0L
    private var octetsSent = 0L
    private var framesEnqueued = 0L
    private var framesDropped = 0L
    private var packetsRetransmitted = 0L
    private var kickstartsSent = 0L

    private var nextRtcpSendTimeMs = 0L
    private var rtcpSendRequested = false

    /**
     * The fixed `(monotonic, wall)` origin all NTP timestamps are derived from.
     *
     * This is openscreen's `NtpTimeConverter`, which is constructed once per session with the
     * current monotonic time and the current wall time and then converts *only* monotonic
     * `Clock::time_point`s (`ntp_time.cc:22-38`). Reading the wall clock afresh for every Sender
     * Report would let an Android NTP resync step the reported timeline sideways, which the
     * receiver's clock-offset smoother reads as a sudden several-hundred-millisecond skew. It
     * also keeps the report's NTP and RTP timestamps derived from the *same* instant, which is
     * the whole point of the pair.
     */
    private val ntpOriginWallMs: Long = wallClock()
    private val ntpOriginMonotonicMs: Long = clock()

    /** The NTP timestamp for a monotonic instant, on this session's fixed origin. */
    private fun ntpAt(nowMs: Long): Long =
        NtpTime.fromUnixMillis(ntpOriginWallMs + (nowMs - ntpOriginMonotonicMs))

    /** Sent-report ids mapped to the NTP timestamps they were sent with, for RTT measurement. */
    private val recentReports = LinkedHashMap<Long, Long>()

    /** Optional event observer. */
    var observer: SenderObserver? = null

    /** True while the receiver is asking for a key frame that has not been enqueued yet. */
    @Synchronized
    fun needsKeyFrame(): Boolean = lastEnqueuedKeyFrameId <= pictureLostAtFrameId

    /**
     * The frame id the next [enqueue] call must use.
     *
     * Zero for the first frame of the stream ([FrameIds.FIRST]), matching
     * `SenderImpl::GetNextFrameId()`.
     */
    @Synchronized
    fun nextFrameId(): Int = lastEnqueuedFrameId + 1

    /** The current smoothed RTT estimate in milliseconds; 0 means "not yet measured". */
    @Synchronized
    fun roundTripTimeMs(): Long = roundTripTimeMs

    /** A snapshot of the counters. */
    @Synchronized
    fun stats(): SenderStats = SenderStats(
        packetsSent = packetsSent,
        octetsSent = octetsSent,
        framesEnqueued = framesEnqueued,
        framesDropped = framesDropped,
        packetsRetransmitted = packetsRetransmitted,
        kickstartsSent = kickstartsSent,
        roundTripTimeMs = roundTripTimeMs,
        checkpointFrameId = checkpointFrameId,
        lastEnqueuedFrameId = lastEnqueuedFrameId,
        fractionLostNumerator = latestFractionLostNumerator,
    )

    /**
     * Whether [enqueue] would accept a frame whose RTP timestamp is [rtpTimestamp], **without**
     * mutating anything or counting a drop.
     *
     * This is the backpressure predicate for video. Video frames are an H.264 reference chain and
     * must never be shed after encoding — dropping frame N leaves frame N+1 referencing content the
     * receiver never got, which datamoshes until the next key frame. So the pump calls this before
     * pulling from the encoder and, when it returns false, simply stops draining the encoder: the
     * backpressure propagates to the capture surface and drops frames **pre-encode**, which is
     * harmless. It mirrors exactly the two limits [enqueue] enforces (the 8-bit id-span window and
     * the in-flight media-duration budget); the payload-size limit is not predictable here but is
     * unreachable for real video.
     */
    @Synchronized
    fun canEnqueue(rtpTimestamp: Long): Boolean {
        val nextId = lastEnqueuedFrameId + 1
        if (nextId - checkpointFrameId > StreamingConstants.MAX_UNACKED_FRAMES) return false
        if (inFlightMediaDurationMs(rtpTimestamp) > maxInFlightMediaDurationMs()) return false
        return true
    }

    /**
     * Encrypts [frame] and queues all of its packets for transmission.
     *
     * @return [EnqueueResult.OK], or the reason the frame was dropped.
     */
    @Synchronized
    fun enqueue(frame: EncodedFrame): EnqueueResult {
        require(frame.frameId == lastEnqueuedFrameId + 1) {
            "frame ids must be contiguous: expected ${lastEnqueuedFrameId + 1}, got ${frame.frameId}"
        }

        if (frame.frameId - checkpointFrameId > StreamingConstants.MAX_UNACKED_FRAMES) {
            framesDropped++
            return EnqueueResult.REACHED_ID_SPAN_LIMIT
        }

        val inFlight = inFlightMediaDurationMs(frame.rtpTimestamp)
        if (inFlight > maxInFlightMediaDurationMs()) {
            framesDropped++
            return EnqueueResult.MAX_DURATION_IN_FLIGHT
        }

        val ciphertext = crypto.encrypt(frame.frameId, frame.data)
        val encrypted = EncryptedFrame(frame, ciphertext)
        val packetCount = packetizer.computeNumberOfPackets(encrypted)
        if (packetCount <= 0) {
            framesDropped++
            return EnqueueResult.PAYLOAD_TOO_LARGE
        }

        slots[frame.frameId] = FrameSlot(
            frame = encrypted,
            sendFlags = BooleanArray(packetCount) { true },
            sentTimes = LongArray(packetCount) { NEVER },
        )
        framesEnqueued++
        lastEnqueuedFrameId = frame.frameId
        if (frame.isKeyFrame) {
            lastEnqueuedKeyFrameId = frame.frameId
            lastEnqueuedKeyFrameTimeMs = clock()
        }

        // Update the lip-sync anchor, keeping the reference time monotonic.
        anchorTimeMs = if (anchorTimeMs == NEVER) frame.captureTimeMs else maxOf(frame.captureTimeMs, anchorTimeMs)
        anchorRtpTimestamp = frame.rtpTimestamp

        // If RTT is still unknown then the receiver has never sent us a Receiver Report, which it
        // can only do after processing a Sender Report from us. Send one right now, before any
        // RTP, so the receiver gets its lip-sync anchor.
        if (roundTripTimeMs == 0L) rtcpSendRequested = true

        return EnqueueResult.OK
    }

    /**
     * Returns the next RTP packet to put on the wire, or `null` when nothing is due.
     *
     * Strictly oldest-first: the scan runs from `checkpoint + 1` to `lastEnqueued` and takes the
     * lowest-numbered flagged packet of the first frame that has one. If nothing is flagged, a
     * kickstart packet may be produced instead.
     *
     * @param nowMs the current monotonic time.
     */
    @Synchronized
    fun nextRtpPacket(nowMs: Long = clock()): ByteArray? {
        var chosen = chooseNextPacketNeedingSend()
        var kickstart = false
        if (chosen == null) {
            val k = chooseKickstartPacket() ?: return null
            if (k.whenMs > nowMs) return null
            chosen = ChosenPacket(k.frameId, k.packetId)
            kickstart = true
        }

        val slot = slots[chosen.frameId] ?: return null
        val packet = packetizer.generatePacket(slot.frame, chosen.packetId)
        slot.sendFlags[chosen.packetId] = false
        slot.sentTimes[chosen.packetId] = nowMs

        packetsSent++
        // RFC 3550 excludes the RTP header from the octet count. This approximation is 4 bytes
        // off on the rare packet carrying the adaptive-latency extension; no known receiver uses
        // the value for anything.
        octetsSent += (packet.size - RtpDefines.BASE_RTP_HEADER_SIZE).coerceAtLeast(0)
        if (kickstart) kickstartsSent++
        return packet
    }

    /**
     * Returns a Sender Report if one is due, or `null`.
     *
     * A report is due when [enqueue] requested an immediate one (before the first RTP packet of
     * the session, and any time RTT is still unknown) or when the 500 ms cadence has elapsed.
     * Only ever one report per burst: RTCP is meant to carry the most up-to-date sender state,
     * so several in one burst would just thrash the receiver.
     */
    @Synchronized
    fun nextRtcpPacket(nowMs: Long = clock()): ByteArray? {
        // Cannot report anything before a frame has ever been enqueued.
        if (anchorTimeMs == NEVER) return null
        if (!rtcpSendRequested && nowMs < nextRtcpSendTimeMs) return null

        val rtpAtSendTime = SenderReportBuilder.advanceRtpTimestamp(
            anchorRtpTimestamp = anchorRtpTimestamp,
            anchorTimeMs = anchorTimeMs,
            sendTimeMs = nowMs,
            rtpTimebase = rtpTimebase,
        )
        val ntp = ntpAt(nowMs)
        val built = reportBuilder.build(ntp, rtpAtSendTime, packetsSent, octetsSent)

        recentReports[built.statusReportId] = nowMs
        while (recentReports.size > MAX_TRACKED_REPORTS) {
            val oldest = recentReports.keys.iterator()
            oldest.next(); oldest.remove()
        }

        rtcpSendRequested = false
        nextRtcpSendTimeMs = nowMs + StreamingConstants.RTCP_REPORT_INTERVAL_MS
        return built.packet
    }

    /**
     * Feeds an inbound RTCP datagram in.
     *
     * @return the parsed feedback when the datagram was well-formed, fresh and addressed to this
     *   stream; `null` when it was malformed, stale, or for another SSRC.
     */
    @Synchronized
    fun handleRtcpPacket(data: ByteArray, length: Int = data.size, nowMs: Long = clock()): RtcpFeedback? {
        rtcpArrivalTimeMs = nowMs
        return when (val result = rtcpParser.parse(data, length, lastEnqueuedFrameId)) {
            is RtcpParseResult.Parsed -> { apply(result.feedback, nowMs); result.feedback }
            RtcpParseResult.Stale -> null
            is RtcpParseResult.Malformed -> null
        }
    }

    private fun apply(fb: RtcpFeedback, nowMs: Long) {
        fb.receiverReport?.let { onReceiverReport(it, nowMs) }
        fb.checkpointFrameId?.let { onCheckpoint(it) }
        if (fb.receivedFrames.isNotEmpty()) onReceiverHasFrames(fb.receivedFrames)
        if (fb.nacks.isNotEmpty()) onNacks(fb.nacks, nowMs)
        if (fb.pictureLossIndicator) onPictureLoss(nowMs)
    }

    // ---- RTCP handlers ---------------------------------------------------------------------

    private fun onReceiverReport(report: app.castmirror.core.cast.streaming.rtcp.ReceiverReportBlock, nowMs: Long) {
        val sentAt = recentReports[report.lastStatusReportId] ?: return
        val totalDelayMs = nowMs - sentAt
        val nonNetworkDelayMs = report.delaySinceLastReportMs()
        // A measurement of zero or less means clock imprecision; 75 microseconds is openscreen's
        // "optimistic wired-Ethernet LAN ping time" floor. Milliseconds cannot represent that, so
        // the floor here is 1 ms.
        var measurement = maxOf(totalDelayMs - nonNetworkDelayMs, MIN_RTT_MS)
        // Cap at the playout delay: a larger RTT means the network is hopeless for streaming
        // anyway, but capping (rather than ignoring) keeps the sender backing off.
        if (measurement > targetPlayoutDelayMs) measurement = targetPlayoutDelayMs.toLong()
        roundTripTimeMs = smoothRoundTripTime(roundTripTimeMs, measurement)
        // Surface the receiver's real loss measurement for the bitrate controller. Held between
        // reports; a receiver seeing no loss keeps this at 0, which must hold the bitrate at ceiling.
        latestFractionLostNumerator = report.fractionLostNumerator
    }

    private fun onCheckpoint(frameId: Int) {
        // A checkpoint above what we could possibly have sent is nonsense; ignore it.
        if (frameId > lastEnqueuedFrameId) return
        while (checkpointFrameId < frameId) {
            checkpointFrameId++
            cancelFrame(checkpointFrameId)
        }
        latestExpectedFrameId = maxOf(latestExpectedFrameId, frameId)
    }

    private fun onReceiverHasFrames(acks: List<Int>) {
        val highest = acks.maxOrNull() ?: return
        if (highest > lastEnqueuedFrameId) return
        for (id in acks) cancelFrame(id)
        latestExpectedFrameId = maxOf(latestExpectedFrameId, highest)
    }

    private fun onNacks(nacks: List<app.castmirror.core.cast.streaming.rtcp.PacketNack>, nowMs: Long) {
        // A NACK may have been issued while the packet was still in flight. Anything sent more
        // recently than one RTT ago is presumed still travelling, and the NACK is stale.
        val tooRecentASendTime = nowMs - roundTripTimeMs
        var retransmitted = 0

        var i = 0
        while (i < nacks.size) {
            val frameId = nacks[i].frameId
            val slot = if (frameId <= lastEnqueuedFrameId) slots[frameId] else null
            if (slot == null) {
                // The receiver NACKed a frame we never sent, which happens when RTCP is
                // reordered. Skip every NACK for that frame.
                while (i < nacks.size && nacks[i].frameId == frameId) i++
                continue
            }
            latestExpectedFrameId = maxOf(latestExpectedFrameId, frameId)

            fun handle(packetId: Int) {
                if (slot.sentTimes[packetId] <= tooRecentASendTime) {
                    slot.sendFlags[packetId] = true
                    retransmitted++
                }
            }

            if (nacks[i].packetId == RtpDefines.ALL_PACKETS_LOST) {
                for (p in slot.sendFlags.indices) handle(p)
                i++
            } else {
                while (i < nacks.size && nacks[i].frameId == frameId) {
                    val p = nacks[i].packetId
                    // A NACK for a packet that does not exist in the frame is logged and ignored.
                    if (p < slot.sendFlags.size) handle(p)
                    i++
                }
            }
        }

        if (retransmitted > 0) {
            packetsRetransmitted += retransmitted
            observer?.onPacketsRetransmitted(retransmitted)
        }
    }

    /**
     * Handles a Picture Loss Indicator.
     *
     * The receiver keeps sending PLIs until a key frame arrives, so a key frame already in flight
     * must not trigger another expensive one — unless [keyFrameCooldownMs] has elapsed since it
     * was enqueued, which catches the case where the in-flight key frame will never land.
     *
     * In-flight frames are deliberately **not** cancelled: the receiver remains the authority on
     * what is ACKed, and corrupted-but-watchable video beats a stall.
     */
    private fun onPictureLoss(nowMs: Long) {
        var keyFrameTimedOut = false
        if (keyFrameCooldownMs > 0 && lastEnqueuedKeyFrameTimeMs != NEVER) {
            keyFrameTimedOut = (nowMs - lastEnqueuedKeyFrameTimeMs) > keyFrameCooldownMs
        }
        if (checkpointFrameId < lastEnqueuedKeyFrameId && !keyFrameTimedOut) return
        pictureLostAtFrameId = maxOf(checkpointFrameId, lastEnqueuedKeyFrameId)
        observer?.onPictureLost()
    }

    /**
     * Cooldown after enqueuing a key frame during which further PLIs are ignored, in
     * milliseconds. Zero disables the timeout, matching libcast's default of "no cooldown".
     */
    var keyFrameCooldownMs: Long = 0

    // ---- internals -------------------------------------------------------------------------

    private fun cancelFrame(frameId: Int) {
        if (slots.remove(frameId) != null) observer?.onFrameCanceled(frameId)
    }

    private class FrameSlot(
        val frame: EncryptedFrame,
        val sendFlags: BooleanArray,
        val sentTimes: LongArray,
    )

    private class ChosenPacket(val frameId: Int, val packetId: Int)

    private class KickstartPacket(val frameId: Int, val packetId: Int, val whenMs: Long)

    private fun chooseNextPacketNeedingSend(): ChosenPacket? {
        var frameId = checkpointFrameId + 1
        while (frameId <= lastEnqueuedFrameId) {
            val slot = slots[frameId]
            if (slot != null) {
                val p = slot.sendFlags.indexOfFirst { it }
                if (p >= 0) return ChosenPacket(frameId, p)
            }
            frameId++
        }
        return null
    }

    private fun chooseKickstartPacket(): KickstartPacket? {
        // If the receiver knows about every queued frame, nothing needs kickstarting.
        if (latestExpectedFrameId >= lastEnqueuedFrameId) return null
        val slot = slots[lastEnqueuedFrameId] ?: return null
        val packetId = slot.sendFlags.size - 1
        val lastSent = slot.sentTimes[packetId]
        if (lastSent == NEVER) return null

        // Be a good network citizen: less aggressive for larger playout-delay windows, and never
        // more often than two round trips. The floor (MIN_KICKSTART_INTERVAL_MS) stops the
        // hair-trigger `targetDelay/20` (= 5 ms at GMS's 100 ms target) from firing redundant
        // kickstarts on a static screen, where a new "repeat" frame only arrives every ~70 ms and
        // is itself enough to keep the receiver informed. With loss ≈ 0 a kickstart is pure waste.
        val desiredIntervalMs = maxOf(targetPlayoutDelayMs / 20L, StreamingConstants.MIN_KICKSTART_INTERVAL_MS)
        val intervalMs = maxOf(desiredIntervalMs, 2 * roundTripTimeMs)
        return KickstartPacket(lastEnqueuedFrameId, packetId, lastSent + intervalMs)
    }

    /** Media duration currently in flight, if a frame at [nextRtpTimestamp] were added. */
    private fun inFlightMediaDurationMs(nextRtpTimestamp: Long): Long {
        val oldest = slots[checkpointFrameId + 1] ?: return 0
        val ticks = nextRtpTimestamp - oldest.frame.frame.rtpTimestamp
        return RtpTime.millisFromTicks(ticks, rtpTimebase)
    }

    /**
     * How much media the sender is willing to keep in flight.
     *
     * Only enough to drive the loss-detection and retransmit feedback loop, which takes about two
     * round trips; floored so the encoder pipeline keeps flowing on fast networks, and capped at
     * a third of the playout-delay window so the majority of the budget stays with the receiver,
     * which needs buffer to absorb retransmissions.
     */
    private fun maxInFlightMediaDurationMs(): Long {
        val upper = maxOf(MIN_SENDER_IN_FLIGHT_MS, targetPlayoutDelayMs / 3L)
        return (roundTripTimeMs * 2).coerceIn(MIN_SENDER_IN_FLIGHT_MS, upper)
    }

    companion object {
        /** Sentinel for "this never happened". */
        const val NEVER = Long.MIN_VALUE

        /** ~2 frames at 30 FPS; the floor on in-flight media duration. */
        const val MIN_SENDER_IN_FLIGHT_MS = 66L

        /** Floor on an RTT measurement, in milliseconds. */
        const val MIN_RTT_MS = 1L

        /** How many recent sender-report ids to keep for RTT matching. */
        const val MAX_TRACKED_REPORTS = 64

        /**
         * The asymmetric RTT smoother: "fast attack, slow decay".
         *
         * React quickly to congestion onset (half weight to a rising measurement), de-noise
         * recovery (one-eighth weight to a falling one), because downward measurements are
         * usually the network settling rather than a sustained improvement.
         */
        fun smoothRoundTripTime(estimate: Long, measurement: Long): Long = when {
            estimate == 0L -> measurement
            measurement > estimate -> (estimate + measurement) / 2
            else -> (7 * estimate + measurement) / 8
        }
    }
}
