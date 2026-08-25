package app.castmirror.core.cast.streaming.rtcp

/**
 * RTCP constants from `openscreen:cast/streaming/impl/rtp_defines.h:142-378`.
 *
 * Cast uses a subset of RFC 3550 plus proprietary extensions. The common header is:
 *
 * ```
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|RC/Subtyp|  Packet Type  |            Length             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * where Length is `payload_size / 4` — payload only, which is RFC 3550's "length in 32-bit words
 * minus one" with the minus-one accounting for this 4-byte header. The payload size must be a
 * multiple of 4.
 */
object RtcpDefines {
    /** Size of the common header. */
    const val COMMON_HEADER_SIZE = 4

    /** The `V=2, P=0` bits that must occupy the top 3 bits of byte 0. */
    const val REQUIRED_VERSION_AND_PADDING_BITS = 0b100

    /** Width of the report-count / subtype field in byte 0. */
    const val REPORT_COUNT_FIELD_NUM_BITS = 5

    // ---- packet types ----

    /** Sender Report. */
    const val PT_SENDER_REPORT = 200

    /** Receiver Report. */
    const val PT_RECEIVER_REPORT = 201

    /** Source Description. Not used by Cast. */
    const val PT_SOURCE_DESCRIPTION = 202

    /** Application-defined; carries the receiver event log. */
    const val PT_APPLICATION_DEFINED = 204

    /** Payload-specific feedback; carries PLI and Cast ACK/NACK feedback. */
    const val PT_PAYLOAD_SPECIFIC = 206

    /** Extended reports; carries the Receiver Reference Time. */
    const val PT_EXTENDED_REPORTS = 207

    // ---- subtypes (low 5 bits of byte 0) ----

    /** With PT 206: "send me a key frame". */
    const val SUBTYPE_PICTURE_LOSS_INDICATOR = 1

    /** With PT 204: the receiver event log. */
    const val SUBTYPE_RECEIVER_LOG = 2

    /** With PT 206: the Cast ACK/NACK feedback message. */
    const val SUBTYPE_FEEDBACK = 15

    // ---- sizes ----

    /** The five words of a Sender Report following the common header. */
    const val SENDER_REPORT_SIZE = 24

    /** A Receiver Report payload is at minimum the 4-byte receiver SSRC. */
    const val RECEIVER_REPORT_SIZE = 4

    /** A report block is 24 bytes. */
    const val REPORT_BLOCK_SIZE = 24

    /** Width of the "cumulative packets lost" field. */
    const val CUMULATIVE_PACKETS_FIELD_NUM_BITS = 24

    /** Denominator of the "fraction lost" field. */
    const val PACKET_FRACTION_LOST_DENOMINATOR = 256

    /** Cast feedback header: receiver SSRC, sender SSRC, 'CAST', then the 4-byte summary word. */
    const val FEEDBACK_HEADER_SIZE = 16

    /** `'C'<<24 | 'A'<<16 | 'S'<<8 | 'T'`. */
    const val CAST_IDENTIFIER_WORD = 0x43415354L

    /** `'C'<<24 | 'S'<<16 | 'T'<<8 | '2'`. */
    const val CST2_IDENTIFIER_WORD = 0x43535432L

    /** `'T'<<24 | 'I'<<16 | 'M'<<8 | 'E'` — a time-sync request that must be ignored, not rejected. */
    const val TIME_SYNC_REQUEST_NAME = 0x54494D45L

    /** Each NACK loss field is 4 bytes. */
    const val FEEDBACK_LOSS_FIELD_SIZE = 4

    /** 'CST2' word plus the feedback-count and octet-count bytes. */
    const val FEEDBACK_ACK_HEADER_SIZE = 6

    /** Minimum CST2 ACK bit-vector length. */
    const val MIN_ACK_BIT_VECTOR_OCTETS = 2

    /** Maximum CST2 ACK bit-vector length. */
    const val MAX_ACK_BIT_VECTOR_OCTETS = 254

    /** An Extended Report payload starts with the author SSRC. */
    const val EXTENDED_REPORT_HEADER_SIZE = 4

    /** Each extended-report block has a 4-byte subheader. */
    const val EXTENDED_REPORT_BLOCK_HEADER_SIZE = 4

    /** RFC 3611 section 4.4: Receiver Reference Time Report. */
    const val RECEIVER_REFERENCE_TIME_BLOCK_TYPE = 4

    /** Size of a Receiver Reference Time block's data: one 64-bit NTP timestamp. */
    const val RECEIVER_REFERENCE_TIME_BLOCK_SIZE = 8

    /** A PLI payload is the two SSRCs. */
    const val PICTURE_LOSS_INDICATOR_HEADER_SIZE = 8

    /** Receiver frame-log message header: RTP timestamp plus the count/timestamp word. */
    const val RECEIVER_FRAME_LOG_MESSAGE_HEADER_SIZE = 8

    /** Each event block inside a frame-log message is 4 bytes. */
    const val RECEIVER_FRAME_LOG_MESSAGE_BLOCK_SIZE = 4

    /** True if [pt] is an RTCP packet type Cast recognises. */
    fun isRtcpPacketType(pt: Int): Boolean = when (pt) {
        PT_SENDER_REPORT, PT_RECEIVER_REPORT, PT_SOURCE_DESCRIPTION,
        PT_APPLICATION_DEFINED, PT_PAYLOAD_SPECIFIC, PT_EXTENDED_REPORTS -> true
        else -> false
    }
}

/**
 * A parsed RTCP Receiver Report block (`rtp_defines.h:214-229`).
 *
 * @property ssrc the "to" SSRC; must equal your sender SSRC or the block belongs to someone else.
 * @property fractionLostNumerator loss fraction, over a denominator of 256.
 * @property cumulativePacketsLost 24-bit cumulative loss count.
 * @property extendedHighestSequenceNumber extended (32-bit) highest sequence number received.
 * @property jitter interarrival jitter in RTP timebase ticks.
 * @property lastStatusReportId middle 32 bits of the NTP timestamp of the last SR received.
 * @property delaySinceLastReport delay since that SR, in units of 1/65536 second.
 */
data class ReceiverReportBlock(
    val ssrc: Long,
    val fractionLostNumerator: Int,
    val cumulativePacketsLost: Int,
    val extendedHighestSequenceNumber: Long,
    val jitter: Long,
    val lastStatusReportId: Long,
    val delaySinceLastReport: Long,
) {
    /** [delaySinceLastReport] converted to milliseconds. */
    fun delaySinceLastReportMs(): Long = delaySinceLastReport * 1000L / 65536L
}

/** One NACKed packet: [packetId] of [frameId], or all packets when the id is `0xFFFF`. */
data class PacketNack(
    /** The full 32-bit frame id, expanded from the 8-bit wire value. */
    val frameId: Int,
    /** The missing packet id, or [app.castmirror.core.cast.streaming.rtp.RtpDefines.ALL_PACKETS_LOST]. */
    val packetId: Int,
)

/**
 * One receiver-side event from an RTCP event-log packet.
 *
 * @property type the 4-bit wire event type; the mapping to names is receiver-internal telemetry.
 * @property timestampMs milliseconds since session start.
 * @property packetId set for packet-received events.
 * @property delayDeltaMs set (signed) for delay-carrying events.
 */
data class ReceiverEventLogEntry(
    val type: Int,
    val timestampMs: Int,
    val packetId: Int? = null,
    val delayDeltaMs: Int? = null,
)

/** A receiver event-log message covering one frame. */
data class ReceiverFrameLogMessage(
    /** Truncated 32-bit RTP timestamp of the frame these events describe. */
    val rtpTimestamp: Long,
    /** The events. */
    val events: List<ReceiverEventLogEntry>,
)

/**
 * Everything a compound RTCP datagram can tell a sender.
 *
 * Fields are `null`/empty when the corresponding RTCP packet was not present.
 */
data class RtcpFeedback(
    /** From an Extended Report: the receiver's reference NTP timestamp for this datagram. */
    val receiverReferenceNtp: Long? = null,
    /** From a Receiver Report. */
    val receiverReport: ReceiverReportBlock? = null,
    /** From Cast feedback: all frames up to and including this one were fully received. */
    val checkpointFrameId: Int? = null,
    /** From Cast feedback: the receiver's current playout delay. */
    val playoutDelayMs: Int? = null,
    /** From CST2: frames received out of order, above the checkpoint. */
    val receivedFrames: List<Int> = emptyList(),
    /** From Cast feedback loss fields: packets to retransmit. */
    val nacks: List<PacketNack> = emptyList(),
    /** True when a Picture Loss Indicator was present: "my decoder is broken, send a key frame". */
    val pictureLossIndicator: Boolean = false,
    /** Receiver event logs, when `receiverRtcpEventLog` was requested in the OFFER. */
    val eventLogs: List<ReceiverFrameLogMessage> = emptyList(),
)
