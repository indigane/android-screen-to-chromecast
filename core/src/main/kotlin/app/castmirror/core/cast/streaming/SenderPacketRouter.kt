package app.castmirror.core.cast.streaming

import app.castmirror.core.StreamingConstants
import app.castmirror.core.util.Bytes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives one or more [Sender]s over a single [UdpTransport].
 *
 * Threading is deliberately minimal: **one send thread** ticking every
 * [StreamingConstants.BURST_INTERVAL_MS], and **one receive thread** blocked on the socket.
 * No coroutines — plain threads and locks, so the core stays JDK-only.
 *
 * ### Pacing
 *
 * Packets go out in bursts every 10 ms. RTCP is *critical priority*: it is sent first with no
 * burst limit (practically bounded by the number of senders), and RTP fills whatever remains of
 * the burst budget. Senders are iterated in SSRC priority order, so audio RTP precedes video RTP
 * within each burst.
 *
 * `maxPacketsPerBurst = (maxBurstBitrate / 8 / packetSize) / (1000 / burstInterval)`, all integer
 * division, floored at 1. With the defaults (`24 << 20` bits/s, 1472-byte packets, 10 ms) that is
 * 21 packets per burst.
 *
 * ### Demultiplexing
 *
 * All streams share one socket. An inbound datagram is routed by reading the SSRC that identifies
 * its author. RTP packets carry the SSRC at offset 8; every RTCP packet type Cast uses carries
 * the report author's SSRC in the first word of its payload, i.e. at offset 4.
 *
 * @param transport the UDP socket abstraction.
 * @param maxPacketSize packet-size assumption used for the burst budget.
 * @param maxBurstBitrate pacer ceiling in bits per second.
 * @param burstIntervalMs pacer tick.
 * @param clock monotonic millisecond clock, injectable for tests.
 */
class SenderPacketRouter(
    private val transport: UdpTransport,
    private val maxPacketSize: Int = StreamingConstants.MAX_RTP_PACKET_SIZE_IPV4,
    maxBurstBitrate: Int = StreamingConstants.MAX_BURST_BITRATE,
    private val burstIntervalMs: Long = StreamingConstants.BURST_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {

    /** Maximum RTP packets emitted per burst. */
    val maxPacketsPerBurst: Int =
        computeMaxPacketsPerBurst(maxBurstBitrate, maxPacketSize, burstIntervalMs)

    private val senders = CopyOnWriteArrayList<Sender>()
    private val running = AtomicBoolean(false)
    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null

    /**
     * The **monotonic** millisecond reading ([clock], i.e. `System.nanoTime() / 1_000_000` by
     * default) at which the last inbound datagram arrived, or [NEVER].
     *
     * Compare it against the same monotonic source, never against `System.currentTimeMillis()`.
     */
    @Volatile
    var lastRtcpArrivalMs: Long = NEVER
        private set

    /** Called when a datagram arrives that matches no registered sender. */
    var onUnroutedPacket: ((ByteArray, Int) -> Unit)? = null

    /**
     * Called on the receive thread with the parsed feedback of each successfully handled inbound
     * RTCP datagram, and the [Sender] it was routed to.
     *
     * Purely observational — the [Sender] has already applied the feedback by the time this runs.
     * Default `null` (no observer). Used by the Android layer's diagnostics to log receiver
     * reports, checkpoints, NACKs and PLIs; keep any work here short, as it runs inline on the
     * receive path.
     */
    var onFeedback: ((Sender, app.castmirror.core.cast.streaming.rtcp.RtcpFeedback) -> Unit)? = null

    /**
     * Registers [sender].
     *
     * Senders are kept sorted by SSRC so that audio (whose SSRCs live in the lower "higher
     * priority" range) is paced ahead of video.
     */
    fun addSender(sender: Sender) {
        senders.add(sender)
        val sorted = senders.sortedBy { it.senderSsrc }
        senders.clear()
        senders.addAll(sorted)
    }

    /** All registered senders, in pacing order. */
    fun senders(): List<Sender> = senders.toList()

    /** Starts the send and receive threads. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        sendThread = Thread({ sendLoop() }, "cast-rtp-send").apply { isDaemon = true; start() }
        receiveThread = Thread({ receiveLoop() }, "cast-rtcp-recv").apply { isDaemon = true; start() }
    }

    /** Stops both threads and closes the transport. */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { transport.close() }
        sendThread?.interrupt()
        receiveThread?.interrupt()
    }

    /** Runs one burst immediately; exposed so tests can drive the pacer deterministically. */
    fun sendBurst(nowMs: Long = clock()): Int {
        var sent = 0
        // RTCP first, at critical priority and with no burst limit.
        for (s in senders) {
            val packet = s.nextRtcpPacket(nowMs) ?: continue
            transport.send(packet)
            sent++
        }
        // RTP fills the remaining budget, higher-priority senders first.
        var budget = maxPacketsPerBurst - sent
        for (s in senders) {
            while (budget > 0) {
                val packet = s.nextRtpPacket(nowMs) ?: break
                transport.send(packet)
                budget--
                sent++
            }
            if (budget <= 0) break
        }
        return sent
    }

    /** Routes one inbound datagram to the sender whose receiver SSRC authored it. */
    fun handleInbound(data: ByteArray, length: Int, nowMs: Long = clock()) {
        val ssrc = authorSsrc(data, length)
        // The socket is unconnected (see DatagramUdpTransport), so anything on the LAN can land
        // here. Only a datagram that is actually from one of our receivers counts as liveness,
        // or the media-inactivity timeout could be held open by unrelated traffic.
        if (ssrc != null && senders.any { it.receiverSsrc == ssrc }) lastRtcpArrivalMs = nowMs
        // Try the sender whose receiver SSRC authored the datagram first, then everyone else.
        // The two-pass shape matters with GMS's fixed SSRCs: it offers video SSRCs 11 and 12, and
        // a reference receiver answers with sender_ssrc + 1, so stream 1's *receiver* SSRC (12)
        // collides with stream 2's *sender* SSRC. Every parser also checks both SSRCs of the
        // pair, so a mis-routed datagram is simply ignored and the second pass finds its owner.
        val ordered = if (ssrc == null) senders.toList()
        else senders.sortedByDescending { it.receiverSsrc == ssrc }

        var handled = false
        for (s in ordered) {
            val feedback = s.handleRtcpPacket(data, length, nowMs)
            if (feedback != null) {
                handled = true
                onFeedback?.invoke(s, feedback)
                break
            }
        }
        if (!handled) onUnroutedPacket?.invoke(data, length)
    }

    private fun sendLoop() {
        while (running.get()) {
            val started = clock()
            try {
                sendBurst(started)
            } catch (e: Exception) {
                if (!running.get()) return
            }
            val elapsed = clock() - started
            val sleep = burstIntervalMs - elapsed
            if (sleep > 0) {
                try { Thread.sleep(sleep) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun receiveLoop() {
        while (running.get()) {
            try {
                val dg = transport.receive(200) ?: continue
                handleInbound(dg.data, dg.length)
            } catch (e: Exception) {
                if (!running.get()) return
            }
        }
    }

    companion object {
        /** Sentinel for "no datagram has ever arrived". */
        const val NEVER = Long.MIN_VALUE

        /**
         * The author SSRC of an inbound datagram, or `null` if it is too short to classify.
         *
         * For RTP the SSRC is at offset 8. For every RTCP type Cast uses, the first word of the
         * payload (offset 4) is the report author's SSRC.
         */
        fun authorSsrc(data: ByteArray, length: Int): Long? {
            if (length < 8) return null
            val packetType = Bytes.u8(data, 1)
            // RTCP packet types occupy 200..207; an RTP second byte is a payload type plus the
            // marker bit, which never lands in that range for Cast's payload types.
            return if (packetType in 200..207) Bytes.u32(data, 4)
            else if (length >= 12) Bytes.u32(data, 8)
            else null
        }

        /** `max(1, (bitrate / 8 / packetSize) / (1000 / intervalMs))`, all integer division. */
        fun computeMaxPacketsPerBurst(maxBurstBitrate: Int, packetSize: Int, burstIntervalMs: Long): Int {
            require(maxBurstBitrate > 0 && packetSize > 0)
            require(burstIntervalMs in 1..1000) { "burst interval must be in (0, 1000] ms" }
            val maxPacketsPerSecond = maxBurstBitrate / 8 / packetSize
            val burstsPerSecond = (1000L / burstIntervalMs).toInt()
            return maxOf(maxPacketsPerSecond / burstsPerSecond, 1)
        }
    }
}
