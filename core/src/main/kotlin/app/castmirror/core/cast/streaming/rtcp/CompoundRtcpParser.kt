package app.castmirror.core.cast.streaming.rtcp

import app.castmirror.core.cast.streaming.rtp.RtpDefines
import app.castmirror.core.util.Bytes

/** The result of parsing one compound RTCP datagram. */
sealed class RtcpParseResult {
    /** The datagram parsed cleanly; [feedback] holds everything it contained. */
    data class Parsed(
        /** Everything the datagram told us. */
        val feedback: RtcpFeedback,
    ) : RtcpParseResult()

    /**
     * The datagram was well-formed but stale: its Receiver Reference Time is older than one
     * already processed. libcast drops such datagrams entirely, since processing compound RTCP
     * out of order is actively harmful and no information is lost (each datagram carries the
     * peer's complete current state).
     */
    object Stale : RtcpParseResult()

    /** The datagram was malformed; discard it. */
    data class Malformed(
        /** Why parsing failed. */
        val reason: String,
    ) : RtcpParseResult()
}

/**
 * Parses compound RTCP datagrams sent by the receiver
 * (`openscreen:cast/streaming/impl/compound_rtcp_parser.cc`).
 *
 * A datagram may concatenate several RTCP packets; the parser loops until the buffer is
 * exhausted. Nothing is reported unless the *entire* datagram parses.
 *
 * Handled packet types:
 *  * **Receiver Report** (201) — loss/jitter statistics and the RTT anchor.
 *  * **Application-defined** (204, subtype 2) — the receiver event log. A `'TIME'` name is a
 *    time-sync request and is ignored **without** erroring; anything else that is not `'CAST'`
 *    is a parse failure.
 *  * **Payload-specific** (206) — subtype 1 is a PLI, subtype 15 is Cast ACK/NACK feedback.
 *  * **Extended reports** (207) — block type 4 is the Receiver Reference Time.
 *
 * @param senderSsrc this stream's sender SSRC.
 * @param receiverSsrc the receiver SSRC for this stream, taken from the ANSWER's `ssrcs`.
 */
class CompoundRtcpParser(
    private val senderSsrc: Long,
    private val receiverSsrc: Long,
) {
    private var latestReceiverNtp: Long? = null
    private var latestEventLogRtpTimestamp: Long = 0

    /**
     * Parses [data].
     *
     * @param maxFeedbackFrameId the highest frame id enqueued so far. The 8-bit checkpoint frame
     *   id is expanded relative to this value.
     * @param length number of valid bytes in [data].
     */
    fun parse(data: ByteArray, length: Int = data.size, maxFeedbackFrameId: Int): RtcpParseResult {
        var pos = 0
        var receiverReferenceNtp: Long? = null
        var receiverReport: ReceiverReportBlock? = null
        var checkpointFrameId: Int? = null
        var playoutDelayMs: Int? = null
        var receivedFrames: List<Int> = emptyList()
        var nacks: List<PacketNack> = emptyList()
        var pli = false
        val eventLogs = ArrayList<ReceiverFrameLogMessage>()

        while (pos < length) {
            if (length - pos < RtcpDefines.COMMON_HEADER_SIZE) return RtcpParseResult.Malformed("truncated RTCP header")
            val byte0 = Bytes.u8(data, pos)
            if ((byte0 ushr RtcpDefines.REPORT_COUNT_FIELD_NUM_BITS) != RtcpDefines.REQUIRED_VERSION_AND_PADDING_BITS) {
                return RtcpParseResult.Malformed("bad RTCP version/padding bits")
            }
            val countOrSubtype = byte0 and 0x1F
            val packetType = Bytes.u8(data, pos + 1)
            if (!RtcpDefines.isRtcpPacketType(packetType)) {
                return RtcpParseResult.Malformed("unknown RTCP packet type $packetType")
            }
            val payloadSize = Bytes.u16(data, pos + 2) * 4
            pos += RtcpDefines.COMMON_HEADER_SIZE
            if (length - pos < payloadSize) return RtcpParseResult.Malformed("truncated RTCP payload")
            val start = pos
            val end = pos + payloadSize
            pos = end

            when (packetType) {
                RtcpDefines.PT_RECEIVER_REPORT -> {
                    val rr = parseReceiverReport(data, start, end, countOrSubtype)
                        ?: return RtcpParseResult.Malformed("bad receiver report")
                    if (rr !== NO_REPORT) receiverReport = rr
                }

                RtcpDefines.PT_APPLICATION_DEFINED -> {
                    if (!parseApplicationDefined(data, start, end, countOrSubtype, eventLogs)) {
                        return RtcpParseResult.Malformed("bad application-defined packet")
                    }
                }

                RtcpDefines.PT_PAYLOAD_SPECIFIC -> when (countOrSubtype) {
                    RtcpDefines.SUBTYPE_PICTURE_LOSS_INDICATOR -> {
                        if (end - start < RtcpDefines.PICTURE_LOSS_INDICATOR_HEADER_SIZE) {
                            return RtcpParseResult.Malformed("truncated PLI")
                        }
                        if (Bytes.u32(data, start) == receiverSsrc && Bytes.u32(data, start + 4) == senderSsrc) {
                            pli = true
                        }
                    }
                    RtcpDefines.SUBTYPE_FEEDBACK -> {
                        val fb = parseFeedback(data, start, end, maxFeedbackFrameId, checkpointFrameId)
                            ?: return RtcpParseResult.Malformed("bad Cast feedback")
                        if (fb !== IGNORED_FEEDBACK) {
                            checkpointFrameId = fb.checkpointFrameId
                            playoutDelayMs = fb.playoutDelayMs
                            receivedFrames = fb.receivedFrames
                            nacks = fb.nacks
                        }
                    }
                    else -> Unit // Unimplemented or not part of the Cast Streaming spec.
                }

                RtcpDefines.PT_EXTENDED_REPORTS -> {
                    val ntp = parseExtendedReports(data, start, end)
                        ?: return RtcpParseResult.Malformed("bad extended report")
                    if (ntp != NO_NTP) receiverReferenceNtp = ntp
                }

                else -> Unit // Ignored.
            }
        }

        // A well-behaved receiver timestamps each datagram with a reference time report. It is
        // not strictly required, so handle its absence; when present, use it to discard
        // reordered datagrams.
        if (receiverReferenceNtp != null) {
            val previous = latestReceiverNtp
            if (previous != null && java.lang.Long.compareUnsigned(receiverReferenceNtp, previous) < 0) {
                return RtcpParseResult.Stale
            }
            latestReceiverNtp = receiverReferenceNtp
        }

        return RtcpParseResult.Parsed(
            RtcpFeedback(
                receiverReferenceNtp = receiverReferenceNtp,
                receiverReport = receiverReport,
                checkpointFrameId = checkpointFrameId,
                playoutDelayMs = playoutDelayMs,
                receivedFrames = receivedFrames,
                nacks = canonicalizeNacks(nacks),
                pictureLossIndicator = pli,
                eventLogs = eventLogs,
            )
        )
    }

    // ---- Receiver Report -------------------------------------------------------------------

    private fun parseReceiverReport(
        data: ByteArray,
        start: Int,
        end: Int,
        reportCount: Int,
    ): ReceiverReportBlock? {
        if (end - start < RtcpDefines.RECEIVER_REPORT_SIZE) return null
        if (Bytes.u32(data, start) != receiverSsrc) return NO_REPORT
        var pos = start + 4
        if (end - pos < RtcpDefines.REPORT_BLOCK_SIZE * reportCount) return null
        var found: ReceiverReportBlock? = NO_REPORT
        repeat(reportCount) {
            val blockSsrc = Bytes.u32(data, pos)
            if (blockSsrc == senderSsrc) {
                val second = Bytes.u32(data, pos + 4)
                found = ReceiverReportBlock(
                    ssrc = blockSsrc,
                    fractionLostNumerator = (second ushr RtcpDefines.CUMULATIVE_PACKETS_FIELD_NUM_BITS).toInt() and 0xFF,
                    cumulativePacketsLost = (second and 0xFFFFFF).toInt(),
                    extendedHighestSequenceNumber = Bytes.u32(data, pos + 8),
                    jitter = Bytes.u32(data, pos + 12),
                    lastStatusReportId = Bytes.u32(data, pos + 16),
                    delaySinceLastReport = Bytes.u32(data, pos + 20),
                )
            }
            pos += RtcpDefines.REPORT_BLOCK_SIZE
        }
        return found
    }

    // ---- Application-defined (receiver event log) -------------------------------------------

    private fun parseApplicationDefined(
        data: ByteArray,
        start: Int,
        end: Int,
        subtype: Int,
        out: MutableList<ReceiverFrameLogMessage>,
    ): Boolean {
        if (end - start < 8) return false
        val ssrc = Bytes.u32(data, start)
        val name = Bytes.u32(data, start + 4)
        // Events not intended for us are simply ignored.
        if (ssrc != receiverSsrc) return true
        if (name != RtcpDefines.CAST_IDENTIFIER_WORD) {
            // Some receivers send 'TIME' sync requests. Ignore them; do not treat as malformed.
            return name == RtcpDefines.TIME_SYNC_REQUEST_NAME
        }
        if (subtype != RtcpDefines.SUBTYPE_RECEIVER_LOG) return true
        return parseFrameLogMessages(data, start + 8, end, out)
    }

    private fun parseFrameLogMessages(
        data: ByteArray,
        from: Int,
        end: Int,
        out: MutableList<ReceiverFrameLogMessage>,
    ): Boolean {
        var pos = from
        while (pos < end) {
            if (end - pos < RtcpDefines.RECEIVER_FRAME_LOG_MESSAGE_HEADER_SIZE) { out.clear(); return false }
            val truncatedRtp = Bytes.u32(data, pos)
            val word = Bytes.u32(data, pos + 4)
            pos += RtcpDefines.RECEIVER_FRAME_LOG_MESSAGE_HEADER_SIZE

            // Low 24 bits: event timestamp base in ms since session start.
            val baseMs = (word and 0xFFFFFF).toInt()
            // High 8 bits: num_events - 1. A wire value of 0 means one event.
            val numEvents = 1 + ((word ushr 24) and 0xFF).toInt()

            val rtpTimestamp = FrameIdExpansion.expandNearest(latestEventLogRtpTimestamp, truncatedRtp, 32)
            val events = ArrayList<ReceiverEventLogEntry>(numEvents)
            repeat(numEvents) {
                if (end - pos < RtcpDefines.RECEIVER_FRAME_LOG_MESSAGE_BLOCK_SIZE) { out.clear(); return false }
                val delayOrPacketId = Bytes.u16(data, pos)
                val typeAndDelta = Bytes.u16(data, pos + 2)
                pos += RtcpDefines.RECEIVER_FRAME_LOG_MESSAGE_BLOCK_SIZE
                val type = typeAndDelta ushr 12
                val timestampMs = baseMs + (typeAndDelta and 0xFFF)
                events.add(
                    ReceiverEventLogEntry(
                        type = type,
                        timestampMs = timestampMs,
                        packetId = if (type in PACKET_RECEIVED_WIRE_TYPES) delayOrPacketId else null,
                        delayDeltaMs =
                            if (type in PACKET_RECEIVED_WIRE_TYPES) null else delayOrPacketId.toShort().toInt(),
                    )
                )
            }
            latestEventLogRtpTimestamp = rtpTimestamp
            out.add(ReceiverFrameLogMessage(rtpTimestamp, events))
        }
        return true
    }

    // ---- Cast feedback ---------------------------------------------------------------------

    private class Feedback(
        val checkpointFrameId: Int,
        val playoutDelayMs: Int,
        val receivedFrames: List<Int>,
        val nacks: List<PacketNack>,
    )

    private fun parseFeedback(
        data: ByteArray,
        start: Int,
        end: Int,
        maxFeedbackFrameId: Int,
        currentCheckpoint: Int?,
    ): Feedback? {
        if (end - start < RtcpDefines.FEEDBACK_HEADER_SIZE) return null
        // Mismatched SSRCs: silently ignore this packet, do not fail the datagram.
        if (Bytes.u32(data, start) != receiverSsrc || Bytes.u32(data, start + 4) != senderSsrc) {
            return IGNORED_FEEDBACK
        }
        // A wrong identifier word IS a parse failure and discards the whole datagram.
        if (Bytes.u32(data, start + 8) != RtcpDefines.CAST_IDENTIFIER_WORD) return null

        var pos = start + 12
        val feedbackFrameId = FrameIdExpansion.expandLessThanOrEqual(maxFeedbackFrameId, Bytes.u8(data, pos))
        val lossFieldCount = Bytes.u8(data, pos + 1)
        val playoutDelayMs = Bytes.u16(data, pos + 2)
        pos += 4

        // Never let the checkpoint move backwards: the sender discards tracking state based on a
        // monotonically non-decreasing checkpoint.
        if (currentCheckpoint != null && currentCheckpoint > feedbackFrameId) return IGNORED_FEEDBACK

        if (end - pos < RtcpDefines.FEEDBACK_LOSS_FIELD_SIZE * lossFieldCount) return null

        val nacks = ArrayList<PacketNack>()
        repeat(lossFieldCount) {
            val frameId = FrameIdExpansion.expandGreaterThan(feedbackFrameId, Bytes.u8(data, pos))
            var packetId = Bytes.u16(data, pos + 1)
            var bits = Bytes.u8(data, pos + 3)
            pos += RtcpDefines.FEEDBACK_LOSS_FIELD_SIZE
            nacks.add(PacketNack(frameId, packetId))
            if (packetId != RtpDefines.ALL_PACKETS_LOST) {
                // Bit i (LSB first) means packet packetId + 1 + i is also missing.
                while (bits != 0) {
                    packetId++
                    if (bits and 1 != 0) nacks.add(PacketNack(frameId, packetId))
                    bits = bits ushr 1
                }
            }
        }

        // Optional CST2 frame-level ACK bit vector. Anything that is not 'CST2' here is treated
        // as harmless trailing garbage, not corruption — many receivers omit it entirely.
        val receivedFrames = ArrayList<Int>()
        if (end - pos >= RtcpDefines.FEEDBACK_ACK_HEADER_SIZE &&
            Bytes.u32(data, pos) == RtcpDefines.CST2_IDENTIFIER_WORD
        ) {
            pos += 4
            pos += 1 // Skip the wrap-around "Feedback Count" field; libcast ignores it too.
            val octets = Bytes.u8(data, pos)
            pos += 1
            if (end - pos < octets) return null
            // "Plus two" and not "plus one" because otherwise the checkpoint should have been
            // a greater value.
            var startingFrameId = feedbackFrameId + 2
            repeat(octets) {
                var bits = Bytes.u8(data, pos)
                pos += 1
                var frameId = startingFrameId
                while (bits != 0) {
                    if (bits and 1 != 0) receivedFrames.add(frameId)
                    frameId++
                    bits = bits ushr 1
                }
                startingFrameId += 8
            }
        }

        return Feedback(feedbackFrameId, playoutDelayMs, receivedFrames, nacks)
    }

    // ---- Extended reports ------------------------------------------------------------------

    private fun parseExtendedReports(data: ByteArray, start: Int, end: Int): Long? {
        if (end - start < RtcpDefines.EXTENDED_REPORT_HEADER_SIZE) return null
        if (Bytes.u32(data, start) != receiverSsrc) return NO_NTP
        var pos = start + 4
        var ntp = NO_NTP
        while (pos < end) {
            if (end - pos < RtcpDefines.EXTENDED_REPORT_BLOCK_HEADER_SIZE) return null
            val blockType = Bytes.u8(data, pos)
            val blockDataSize = Bytes.u16(data, pos + 2) * 4
            pos += RtcpDefines.EXTENDED_REPORT_BLOCK_HEADER_SIZE
            if (end - pos < blockDataSize) return null
            if (blockType == RtcpDefines.RECEIVER_REFERENCE_TIME_BLOCK_TYPE) {
                if (blockDataSize != RtcpDefines.RECEIVER_REFERENCE_TIME_BLOCK_SIZE) return null
                ntp = Bytes.u64(data, pos)
            }
            pos += blockDataSize
        }
        return ntp
    }

    // ---- NACK canonicalization -------------------------------------------------------------

    /**
     * Sorts and de-duplicates NACKs so that consumers can make simplifying assumptions:
     * ordering is lexicographic except that [RtpDefines.ALL_PACKETS_LOST] sorts *first* within a
     * frame, and any frame carrying it drops its other entries as redundant.
     */
    private fun canonicalizeNacks(input: List<PacketNack>): List<PacketNack> {
        if (input.isEmpty()) return input
        val sorted = input.sortedWith(
            compareBy<PacketNack> { it.frameId }
                .thenBy { (it.packetId + 1) and 0xFFFF }
        )
        val out = ArrayList<PacketNack>(sorted.size)
        for (n in sorted) {
            val kept = out.lastOrNull()
            if (kept == null ||
                kept.frameId != n.frameId ||
                (kept.packetId != RtpDefines.ALL_PACKETS_LOST && kept.packetId != n.packetId)
            ) {
                out.add(n)
            }
        }
        return out
    }

    private companion object {
        /** Sentinel meaning "no receiver report for us in this packet". */
        val NO_REPORT = ReceiverReportBlock(0, 0, 0, 0, 0, 0, 0)

        /** Sentinel meaning "feedback present but not for this SSRC pair". */
        val IGNORED_FEEDBACK = Feedback(0, 0, emptyList(), emptyList())

        /** Sentinel meaning "no reference time in this extended report". */
        const val NO_NTP = -1L

        /**
         * Wire event types meaning "packet received", for which the leading 16-bit field is a
         * packet id rather than a signed delay delta: legacy audio (4), legacy video (8) and the
         * new unified type (14) (`statistics_common.h:53-75`).
         */
        val PACKET_RECEIVED_WIRE_TYPES = setOf(4, 8, 14)
    }
}
