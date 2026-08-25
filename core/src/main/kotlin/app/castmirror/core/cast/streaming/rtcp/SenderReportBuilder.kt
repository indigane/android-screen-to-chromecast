package app.castmirror.core.cast.streaming.rtcp

import app.castmirror.core.util.ByteWriter

/**
 * The Sender Report a Cast sender periodically emits, and its `StatusReportId`.
 *
 * @property packet the serialized RTCP packet.
 * @property statusReportId the middle 32 bits of the SR's NTP timestamp. The receiver echoes it
 *   in the Receiver Report's `last_status_report_id`, which is how RTT is measured.
 * @property ntpTimestamp the full 64-bit NTP timestamp written into the packet.
 */
class BuiltSenderReport(
    val packet: ByteArray,
    val statusReportId: Long,
    val ntpTimestamp: Long,
)

/**
 * Builds RTCP Sender Reports (`openscreen:cast/streaming/impl/sender_report_builder.cc`).
 *
 * This is the **only** RTCP packet a Cast sender is required to generate.
 *
 * ```
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | 0x80|RC  |     200       |      length = (24+24*RC)/4          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        SSRC of Sender                         |
 * |                    NTP Timestamp (64-bit)                     |
 * |                         RTP Timestamp                         |
 * |                     Sender's Packet Count                     |
 * |                     Sender's Octet Count                      |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *         ...optionally one 24-byte Report Block...
 * ```
 *
 * Cast restricts SR/RR to "zero or one" report block, unlike RFC 3550's "zero or more". A sender
 * that has received nothing sends `RC=0` with no report blocks, which is what this builder does.
 *
 * @param senderSsrc this stream's sender SSRC.
 */
class SenderReportBuilder(private val senderSsrc: Long) {

    /**
     * Builds a Sender Report.
     *
     * The `(NTP, RTP)` pair is the receiver's lip-sync anchor: it says "at wall-clock time T the
     * media clock read R". libcast recomputes it at *send* time rather than reusing the last
     * frame's timestamp, and so must you — using the raw frame timestamp causes drifting A/V sync.
     * See [advanceRtpTimestamp].
     *
     * @param ntpTimestamp the 64-bit NTP timestamp of the moment this packet is sent.
     * @param rtpTimestamp the media clock at that same instant, in this stream's timebase.
     * @param packetCount cumulative RTP packets sent this session.
     * @param octetCount cumulative payload octets sent this session, excluding RTP headers.
     */
    fun build(
        ntpTimestamp: Long,
        rtpTimestamp: Long,
        packetCount: Long,
        octetCount: Long,
    ): BuiltSenderReport {
        val w = ByteWriter(RtcpDefines.COMMON_HEADER_SIZE + RtcpDefines.SENDER_REPORT_SIZE)
        writeCommonHeader(
            w,
            reportCountOrSubtype = 0,
            packetType = RtcpDefines.PT_SENDER_REPORT,
            payloadSize = RtcpDefines.SENDER_REPORT_SIZE,
        )
        w.u32(senderSsrc and 0xFFFFFFFFL)
        w.u64(ntpTimestamp)
        w.u32(rtpTimestamp and 0xFFFFFFFFL)
        w.u32(packetCount and 0xFFFFFFFFL)
        w.u32(octetCount and 0xFFFFFFFFL)
        return BuiltSenderReport(w.toByteArray(), NtpTime.toStatusReportId(ntpTimestamp), ntpTimestamp)
    }

    companion object {
        /**
         * Writes an RTCP common header.
         *
         * @param reportCountOrSubtype the report count (SR/RR) or Cast subtype (APP/PSFB).
         * @param packetType the RTCP packet type.
         * @param payloadSize payload bytes following this header; must be a multiple of 4.
         */
        fun writeCommonHeader(w: ByteWriter, reportCountOrSubtype: Int, packetType: Int, payloadSize: Int) {
            require(payloadSize % 4 == 0) { "RTCP payload size $payloadSize is not a multiple of 4" }
            require(reportCountOrSubtype in 0..31) { "report count/subtype out of range" }
            w.u8((RtcpDefines.REQUIRED_VERSION_AND_PADDING_BITS shl RtcpDefines.REPORT_COUNT_FIELD_NUM_BITS) or reportCountOrSubtype)
            w.u8(packetType)
            w.u16(payloadSize / 4)
        }

        /**
         * Advances an anchor `(referenceTimeMs, rtpTimestamp)` pair to [sendTimeMs].
         *
         * This is `sender_impl.cc:285-291`: take the last enqueued frame's reference time and RTP
         * timestamp, move the reference time to the actual send instant, and move the RTP
         * timestamp by the same interval converted into the RTP timebase.
         *
         * @param anchorRtpTimestamp the RTP timestamp of the anchor frame.
         * @param anchorTimeMs the monotonic time of the anchor frame, in milliseconds.
         * @param sendTimeMs the monotonic time this report is being sent, in milliseconds.
         * @param rtpTimebase ticks per second for this stream.
         */
        fun advanceRtpTimestamp(
            anchorRtpTimestamp: Long,
            anchorTimeMs: Long,
            sendTimeMs: Long,
            rtpTimebase: Int,
        ): Long = anchorRtpTimestamp + (sendTimeMs - anchorTimeMs) * rtpTimebase / 1000L
    }
}

/**
 * Builds a Picture Loss Indicator (PT 206, subtype 1).
 *
 * Senders do not normally send PLIs — receivers do — but the builder is useful for tests and for
 * a future receiver implementation.
 *
 * @param receiverSsrc the SSRC of the report author.
 * @param senderSsrc the SSRC of the media sender the PLI is aimed at.
 */
fun buildPictureLossIndicator(receiverSsrc: Long, senderSsrc: Long): ByteArray {
    val w = ByteWriter(RtcpDefines.COMMON_HEADER_SIZE + RtcpDefines.PICTURE_LOSS_INDICATOR_HEADER_SIZE)
    SenderReportBuilder.writeCommonHeader(
        w,
        reportCountOrSubtype = RtcpDefines.SUBTYPE_PICTURE_LOSS_INDICATOR,
        packetType = RtcpDefines.PT_PAYLOAD_SPECIFIC,
        payloadSize = RtcpDefines.PICTURE_LOSS_INDICATOR_HEADER_SIZE,
    )
    w.u32(receiverSsrc and 0xFFFFFFFFL)
    w.u32(senderSsrc and 0xFFFFFFFFL)
    return w.toByteArray()
}
