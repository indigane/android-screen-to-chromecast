package app.castmirror.core.cast.streaming

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One received UDP datagram. */
class ReceivedDatagram(
    /** The payload. */
    val data: ByteArray,
    /** Number of valid bytes in [data]. */
    val length: Int,
)

/**
 * The UDP abstraction the media path runs over.
 *
 * All RTP and RTCP for **all** streams of a session are multiplexed on one socket, demultiplexed
 * by SSRC and packet type. The receiver replies to the source address and port of your RTP, so
 * a second socket must never be opened.
 */
interface UdpTransport : AutoCloseable {
    /** Sends one datagram to the negotiated receiver endpoint. */
    fun send(data: ByteArray, length: Int = data.size)

    /**
     * Blocks for up to [timeoutMs] waiting for a datagram.
     *
     * @return the datagram, or `null` on timeout.
     */
    fun receive(timeoutMs: Long): ReceivedDatagram?
}

/**
 * A [UdpTransport] over a real [DatagramChannel].
 *
 * The channel is **bound** to an ephemeral local port and left *unconnected*, exactly as
 * openscreen's `Environment` does (`environment.cc:35` binds, and `environment.cc:118` sends with
 * an explicit `SendMessage(packet, remote_endpoint_)`). It is tempting to `connect()` the socket —
 * it filters stray datagrams and turns ICMP port-unreachable into a visible exception — but it
 * also makes the kernel discard every datagram whose source is not *exactly*
 * `answer.ipaddr:answer.udpPort`. A multi-homed receiver that answers with one address and
 * transmits from another, or one that sources RTCP from a different port, then looks identical to
 * a dead link: no RTCP, no ACKs, no retransmits, no picture. Datagrams are instead accepted from
 * any source and filtered by SSRC, which is what the RTCP parsers already do.
 *
 * @param host receiver address — `answer.ipaddr` when present, else the control-channel host.
 * @param port the `udpPort` from the ANSWER.
 * @param sendBufferBytes socket send buffer; generous sizing absorbs pacer bursts.
 * @param dscp DSCP value to request via the IP traffic class, or `null`. Best effort — Android
 *   silently ignores it on many devices.
 */
class DatagramUdpTransport(
    host: String,
    port: Int,
    sendBufferBytes: Int = 256 * 1024,
    dscp: Int? = null,
) : UdpTransport {

    /** Where RTP and RTCP are sent. Passed on every send, since the socket is not connected. */
    private val remote = InetSocketAddress(host, port)

    private val channel: DatagramChannel = DatagramChannel.open().apply {
        bind(InetSocketAddress(0))
        setOption(StandardSocketOptions.SO_SNDBUF, sendBufferBytes)
        setOption(StandardSocketOptions.SO_RCVBUF, sendBufferBytes)
        if (dscp != null) {
            // DSCP occupies the top 6 bits of the 8-bit IP traffic class field.
            runCatching { setOption(StandardSocketOptions.IP_TOS, (dscp shl 2) and 0xFF) }
        }
        configureBlocking(true)
    }

    /** The local port the socket was bound to. */
    val localPort: Int get() = (channel.localAddress as InetSocketAddress).port

    override fun send(data: ByteArray, length: Int) {
        channel.send(ByteBuffer.wrap(data, 0, length), remote)
    }

    override fun receive(timeoutMs: Long): ReceivedDatagram? {
        // DatagramChannel has no read timeout of its own, so drive the underlying socket, which
        // does. A fresh buffer per datagram keeps the returned array safely owned by the caller.
        channel.socket().soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val packet = java.net.DatagramPacket(ByteArray(MAX_DATAGRAM_SIZE), MAX_DATAGRAM_SIZE)
        return try {
            channel.socket().receive(packet)
            ReceivedDatagram(packet.data, packet.length)
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }

    companion object {
        /** Receive buffer size; comfortably above any Cast RTP or compound RTCP datagram. */
        const val MAX_DATAGRAM_SIZE = 2048
    }
}

/**
 * An in-memory [UdpTransport] for tests.
 *
 * Sent packets accumulate in [sent]; datagrams pushed with [deliver] are handed to [receive].
 */
class FakeUdpTransport : UdpTransport {
    private val inbound = LinkedBlockingQueue<ReceivedDatagram>()
    private val outbound = ArrayList<ByteArray>()
    private var open = true

    /** Every packet sent so far, in order. */
    val sent: List<ByteArray> get() = synchronized(outbound) { ArrayList(outbound) }

    /** Number of packets sent so far. */
    val sentCount: Int get() = synchronized(outbound) { outbound.size }

    /** Queues [data] to be returned by the next [receive]. */
    fun deliver(data: ByteArray) {
        inbound.put(ReceivedDatagram(data, data.size))
    }

    override fun send(data: ByteArray, length: Int) {
        check(open) { "transport is closed" }
        synchronized(outbound) { outbound.add(data.copyOf(length)) }
    }

    override fun receive(timeoutMs: Long): ReceivedDatagram? =
        inbound.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() { open = false }
}
