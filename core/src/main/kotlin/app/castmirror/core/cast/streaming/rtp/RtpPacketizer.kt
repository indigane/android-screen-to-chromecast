package app.castmirror.core.cast.streaming.rtp

import app.castmirror.core.StreamingConstants
import app.castmirror.core.cast.streaming.EncryptedFrame
import app.castmirror.core.util.ByteWriter
import java.util.Random

/**
 * Turns a logical sequence of encrypted frames into Cast RTP packets.
 *
 * One instance per SSRC — audio and video have independent sequence-number and frame-id spaces.
 * Ported from `openscreen:cast/streaming/impl/rtp_packetizer.cc`.
 *
 * ### Wire layout
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ ^
 * |V=2|P|X| CC=0  |M|      PT     |      sequence number          | |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+RTP
 * |                         RTP timestamp                         | |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ |
 * |         synchronization source (SSRC) identifier              | v
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |K|R| EXT count |  FID          |              PID              | ^
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+Cast
 * |             Max PID           |  ref frame id | ext | payload   v
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * Points that are easy to get wrong:
 *  * Byte 0 is **always** exactly `0x80`.
 *  * The marker bit means "last packet of this frame", not RTP's usual semantics.
 *  * Byte 12 bit 6 ("reference frame id present") is set **unconditionally**, and the
 *    reference-frame-id byte is always emitted, so the base header is 19 bytes, not 18.
 *  * The sequence number increments on **every** packet emitted, retransmissions included — that
 *    is what makes the receiver's loss statistics meaningful.
 *  * Chunking is byte-oriented and codec-agnostic: no NAL alignment, no fragmentation header.
 *
 * @param payloadType the RTP payload type; must agree with the OFFER's `rtpPayloadType`.
 * @param senderSsrc this stream's sender SSRC, as offered.
 * @param maxPacketSize total on-the-wire packet size ceiling; 1472 for IPv4, 1452 for IPv6.
 * @param initialSequenceNumber starting sequence number. Cast has always used a random start;
 *   the reason is lost to history but several spec versions and implementations require it.
 */
class RtpPacketizer(
    private val payloadType: Int,
    private val senderSsrc: Long,
    val maxPacketSize: Int = StreamingConstants.MAX_RTP_PACKET_SIZE_IPV4,
    initialSequenceNumber: Int = randomSequenceNumberStart(),
) {
    init {
        require(RtpPayloadTypes.isValid(payloadType)) { "invalid RTP payload type $payloadType" }
        require(maxPacketSize > RtpDefines.MAX_RTP_HEADER_SIZE) {
            "maxPacketSize $maxPacketSize must exceed ${RtpDefines.MAX_RTP_HEADER_SIZE}"
        }
    }

    private var sequenceNumber: Int = initialSequenceNumber and 0xFFFF

    /**
     * Bytes of payload carried per packet.
     *
     * Note that the 4 adaptive-latency bytes are **always** reserved, even for the vast majority
     * of packets that do not carry the extension — those packets simply come out 4 bytes below
     * the MTU. Doing otherwise would make `computeNumberOfPackets` disagree between the packet-0
     * and packet-N paths, and every packet advertises `max_packet_id`.
     *
     * With the IPv4 default this is `1472 - 23 = 1449`.
     */
    val maxPayloadSize: Int get() = maxPacketSize - RtpDefines.MAX_RTP_HEADER_SIZE

    /** The sequence number that will be used by the next [generatePacket] call. */
    fun peekSequenceNumber(): Int = sequenceNumber

    /**
     * Number of packets the frame of [encryptedLength] bytes will be split across.
     *
     * Always at least 1 — a zero-byte frame still produces one packet, because some audio codecs
     * use zero bytes to represent a period of silence.
     *
     * @return the packet count, or `-1` if the frame is too large to represent
     *   (more than [RtpDefines.MAX_ALLOWED_FRAME_PACKET_ID] + 1 packets).
     */
    fun computeNumberOfPackets(encryptedLength: Int): Int {
        val n = maxOf(1, (encryptedLength + maxPayloadSize - 1) / maxPayloadSize)
        return if (n <= RtpDefines.MAX_ALLOWED_FRAME_PACKET_ID) n else -1
    }

    /** Number of packets [frame] will be split across. */
    fun computeNumberOfPackets(frame: EncryptedFrame): Int =
        computeNumberOfPackets(frame.ciphertext.size)

    /**
     * Serializes packet [packetId] of [frame].
     *
     * Call this in the order packets will actually be transmitted, including for
     * retransmissions — each call consumes a sequence number.
     *
     * @throws IllegalArgumentException if [packetId] is out of range for the frame.
     */
    fun generatePacket(frame: EncryptedFrame, packetId: Int): ByteArray {
        val numPackets = computeNumberOfPackets(frame)
        require(numPackets > 0) { "frame is too large to packetize" }
        require(packetId in 0 until numPackets) { "packetId $packetId out of range [0,$numPackets)" }

        val isLastPacket = packetId == numPackets - 1
        val includeAdaptiveLatency = packetId == 0 && frame.frame.newPlayoutDelayMs > 0

        val chunkStart = maxPayloadSize * packetId
        val chunkSize =
            if (isLastPacket) frame.ciphertext.size - chunkStart else maxPayloadSize

        val w = ByteWriter(RtpDefines.MAX_RTP_HEADER_SIZE + maxOf(chunkSize, 0))

        // ---- RTP header (12 bytes) ----
        w.u8(RtpDefines.REQUIRED_FIRST_BYTE)
        w.u8((if (isLastPacket) RtpDefines.MARKER_BIT_MASK else 0) or (payloadType and RtpDefines.PAYLOAD_TYPE_MASK))
        w.u16(sequenceNumber)
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        w.u32(frame.frame.rtpTimestamp and 0xFFFFFFFFL)
        w.u32(senderSsrc and 0xFFFFFFFFL)

        // ---- Cast header (7 bytes, reference-frame-id byte always present) ----
        w.u8(
            (if (frame.frame.isKeyFrame) RtpDefines.KEY_FRAME_BIT_MASK else 0) or
                RtpDefines.HAS_REFERENCE_FRAME_ID_BIT_MASK or
                (if (includeAdaptiveLatency) 1 else 0)
        )
        w.u8(frame.frame.frameId and 0xFF)
        w.u16(packetId)
        w.u16(numPackets - 1)
        w.u8(frame.frame.referencedFrameId and 0xFF)

        // ---- Optional adaptive-latency extension (4 bytes) ----
        if (includeAdaptiveLatency) {
            w.u16(RtpDefines.ADAPTIVE_LATENCY_FIRST_WORD)
            w.u16(frame.frame.newPlayoutDelayMs)
        }

        if (chunkSize > 0) w.bytes(frame.ciphertext, chunkStart, chunkSize)
        return w.toByteArray()
    }

    companion object {
        private val random = Random()

        /**
         * A random 16-bit sequence-number start.
         *
         * "The reason for using a random number instead of zero is unclear, but this has existed
         * both in several versions of the Cast Streaming spec and in other implementations for
         * many years." A non-cryptographic PRNG is fine.
         */
        fun randomSequenceNumberStart(): Int = random.nextInt(0x10000)
    }
}
