package app.castmirror.core

import app.castmirror.core.cast.streaming.rtcp.CompoundRtcpParser
import app.castmirror.core.cast.streaming.rtcp.FrameIdExpansion
import app.castmirror.core.cast.streaming.rtcp.NtpTime
import app.castmirror.core.cast.streaming.rtcp.PacketNack
import app.castmirror.core.cast.streaming.rtcp.RtcpDefines
import app.castmirror.core.cast.streaming.rtcp.RtcpParseResult
import app.castmirror.core.cast.streaming.rtcp.SenderReportBuilder
import app.castmirror.core.cast.streaming.rtcp.buildPictureLossIndicator
import app.castmirror.core.cast.streaming.rtp.RtpDefines
import app.castmirror.core.util.ByteWriter
import app.castmirror.core.util.Bytes

private const val SENDER_SSRC = 62811L    // 0x0000F55B
private const val RECEIVER_SSRC = 62812L  // 0x0000F55C

/** Builds a Cast feedback ('CAST') RTCP packet by hand, exactly per spec section 7.4. */
private fun buildCastFeedback(
    receiverSsrc: Long = RECEIVER_SSRC,
    senderSsrc: Long = SENDER_SSRC,
    checkpoint: Int = 5,
    playoutDelayMs: Int = 400,
    lossFields: List<Triple<Int, Int, Int>> = emptyList(),  // (frameId low byte, packetId, bit vector)
    cst2: Pair<Int, List<Int>>? = null,                     // (feedback count, ACK bit vector octets)
    identifier: Long = RtcpDefines.CAST_IDENTIFIER_WORD,
): ByteArray {
    val body = ByteWriter(64)
    body.u32(receiverSsrc)
    body.u32(senderSsrc)
    body.u32(identifier)
    body.u8(checkpoint)
    body.u8(lossFields.size)
    body.u16(playoutDelayMs)
    for ((frameId, packetId, bits) in lossFields) {
        body.u8(frameId)
        body.u16(packetId)
        body.u8(bits)
    }
    if (cst2 != null) {
        body.u32(RtcpDefines.CST2_IDENTIFIER_WORD)
        body.u8(cst2.first)
        body.u8(cst2.second.size)
        for (o in cst2.second) body.u8(o)
        // Pad to a 32-bit boundary.
        while ((body.size % 4) != 0) body.u8(0)
    }
    val payload = body.toByteArray()
    val out = ByteWriter(payload.size + 4)
    SenderReportBuilder.writeCommonHeader(
        out,
        reportCountOrSubtype = RtcpDefines.SUBTYPE_FEEDBACK,
        packetType = RtcpDefines.PT_PAYLOAD_SPECIFIC,
        payloadSize = payload.size,
    )
    out.bytes(payload)
    return out.toByteArray()
}

/** Builds a Receiver Report with one report block. */
private fun buildReceiverReport(
    lastStatusReportId: Long,
    delaySinceLastReportUnits: Long,
    fractionLost: Int = 0,
    cumulativeLost: Int = 0,
    highestSeq: Long = 1000,
    jitter: Long = 5,
): ByteArray {
    val body = ByteWriter(32)
    body.u32(RECEIVER_SSRC)
    body.u32(SENDER_SSRC)
    body.u32((fractionLost.toLong() shl 24) or (cumulativeLost.toLong() and 0xFFFFFF))
    body.u32(highestSeq)
    body.u32(jitter)
    body.u32(lastStatusReportId)
    body.u32(delaySinceLastReportUnits)
    val payload = body.toByteArray()
    val out = ByteWriter(payload.size + 4)
    SenderReportBuilder.writeCommonHeader(out, 1, RtcpDefines.PT_RECEIVER_REPORT, payload.size)
    out.bytes(payload)
    return out.toByteArray()
}

/** Builds an Extended Report carrying a Receiver Reference Time block. */
private fun buildReceiverReferenceTime(ntp: Long): ByteArray {
    val body = ByteWriter(16)
    body.u32(RECEIVER_SSRC)
    body.u8(RtcpDefines.RECEIVER_REFERENCE_TIME_BLOCK_TYPE)
    body.u8(0)      // reserved
    body.u16(2)     // block length in 32-bit words
    body.u64(ntp)
    val payload = body.toByteArray()
    val out = ByteWriter(payload.size + 4)
    SenderReportBuilder.writeCommonHeader(out, 0, RtcpDefines.PT_EXTENDED_REPORTS, payload.size)
    out.bytes(payload)
    return out.toByteArray()
}

/** NTP conversion, Sender Report layout, and compound RTCP parsing. */
fun runRtcpTests() {
    T.section("NTP timestamps")

    T.assertEquals("NTP epoch offset", 2_208_988_800L, NtpTime.EPOCH_OFFSET_SECONDS)
    T.assertEquals("unix epoch maps to the NTP epoch offset", 2_208_988_800L shl 32, NtpTime.fromUnixMillis(0))
    T.assertEquals("one second later", 2_208_988_801L, NtpTime.fromUnixMillis(1000) ushr 32)
    T.assertEquals("half a second is 0x80000000 in the fraction", 0x80000000L, NtpTime.fromUnixMillis(500) and 0xFFFFFFFFL)
    T.assertEquals("round-trips to unix millis", 1_700_000_123L * 1000 + 456, NtpTime.toUnixMillis(NtpTime.fromUnixMillis(1_700_000_123L * 1000 + 456)))

    // The static assertion openscreen itself makes about ToStatusReportId.
    T.assertEquals(
        "status report id is the middle 32 bits",
        0xa3b4c5d6L,
        NtpTime.toStatusReportId(0x0192a3b4c5d6e7f8UL.toLong()),
    )
    val ntp = 0x0192a3b4c5d6e7f8UL.toLong()
    T.assertEquals(
        "recentReportNtp reconstructs the timestamp",
        0x0192a3b4c5d60000UL.toLong(),
        NtpTime.recentReportNtp(NtpTime.toStatusReportId(ntp), ntp),
    )

    T.section("RTCP Sender Report layout")

    val builder = SenderReportBuilder(SENDER_SSRC)
    val sr = builder.build(
        ntpTimestamp = 0x0192a3b4c5d6e7f8UL.toLong(),
        rtpTimestamp = 0x00BB8000L,
        packetCount = 5,
        octetCount = 7000,   // 0x1B58
    )
    val expected = byteArrayOf(
        0x80.toByte(),                    // V=2, P=0, RC=0
        0xC8.toByte(),                    // PT 200 (Sender Report)
        0x00, 0x06,                       // payload size 24 bytes / 4
        0x00, 0x00, 0xF5.toByte(), 0x5B,  // SSRC of sender
        0x01, 0x92.toByte(), 0xA3.toByte(), 0xB4.toByte(),
        0xC5.toByte(), 0xD6.toByte(), 0xE7.toByte(), 0xF8.toByte(),   // NTP timestamp
        0x00, 0xBB.toByte(), 0x80.toByte(), 0x00,                     // RTP timestamp
        0x00, 0x00, 0x00, 0x05,                                       // packet count
        0x00, 0x00, 0x1B, 0x58,                                       // octet count
    )
    T.assertBytes("Sender Report bytes", expected, sr.packet)
    T.assertEquals("Sender Report is 28 bytes", 28, sr.packet.size)
    T.assertEquals("status report id", 0xa3b4c5d6L, sr.statusReportId)

    T.assertEquals(
        "RTP timestamp advances with wall time",
        90_000L * 2 + 1000,
        SenderReportBuilder.advanceRtpTimestamp(1000, 500, 2500, 90_000),
    )

    T.section("RTCP Picture Loss Indicator")

    val pli = buildPictureLossIndicator(RECEIVER_SSRC, SENDER_SSRC)
    T.assertBytes(
        "PLI bytes",
        byteArrayOf(
            0x81.toByte(),                    // V=2, subtype 1 (PLI)
            0xCE.toByte(),                    // PT 206 (payload specific)
            0x00, 0x02,                       // payload 8 bytes / 4
            0x00, 0x00, 0xF5.toByte(), 0x5C,  // SSRC of receiver
            0x00, 0x00, 0xF5.toByte(), 0x5B,  // SSRC of sender
        ),
        pli,
    )

    val parser = CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC)
    val pliResult = parser.parse(pli, maxFeedbackFrameId = 10)
    T.check("PLI parses", pliResult is RtcpParseResult.Parsed)
    T.check("PLI sets the flag", (pliResult as RtcpParseResult.Parsed).feedback.pictureLossIndicator)

    val wrongSsrcPli = buildPictureLossIndicator(999L, SENDER_SSRC)
    val wrongResult = CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(wrongSsrcPli, maxFeedbackFrameId = 10)
    T.check(
        "a PLI from an unknown SSRC is ignored, not rejected",
        wrongResult is RtcpParseResult.Parsed && !wrongResult.feedback.pictureLossIndicator,
    )

    T.section("Cast feedback (ACK/NACK) parsing")

    // Checkpoint 5, one loss field for frame 7 packet 3 with bit vector 0b101 (packets 4 and 6),
    // and a CST2 ACK vector marking frames 7 and 16 as received out of order.
    val feedback = buildCastFeedback(
        checkpoint = 5,
        playoutDelayMs = 400,
        lossFields = listOf(Triple(7, 3, 0b101)),
        cst2 = 1 to listOf(0x01, 0x02),
    )
    T.assertEquals("feedback byte 0 is V=2 with subtype 15", 0x8F, Bytes.u8(feedback, 0))
    T.assertEquals("feedback byte 1 is PT 206", 0xCE, Bytes.u8(feedback, 1))
    T.assertEquals("feedback payload size is a multiple of 4", 0, (feedback.size - 4) % 4)
    T.assertEquals("'CAST' identifier word", 0x43415354L, Bytes.u32(feedback, 12))

    val fbResult = CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(feedback, maxFeedbackFrameId = 10)
    T.check("feedback parses", fbResult is RtcpParseResult.Parsed, (fbResult as? RtcpParseResult.Malformed)?.reason ?: "")
    val fb = (fbResult as RtcpParseResult.Parsed).feedback
    T.assertEquals("checkpoint frame id", 5, fb.checkpointFrameId)
    T.assertEquals("playout delay", 400, fb.playoutDelayMs)
    T.assertEquals(
        "NACK list expands the bit vector (bit 0 -> +1, bit 2 -> +3)",
        listOf(PacketNack(7, 3), PacketNack(7, 4), PacketNack(7, 6)),
        fb.nacks,
    )
    T.assertEquals("CST2 ACK vector: checkpoint+2 and the second octet's bit 1", listOf(7, 16), fb.receivedFrames)

    // kAllPacketsLost means "no packet of that frame arrived at all"; the bit vector is ignored.
    val allLost = buildCastFeedback(lossFields = listOf(Triple(9, RtpDefines.ALL_PACKETS_LOST, 0xFF)))
    val allLostFb = (CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(allLost, maxFeedbackFrameId = 12)
        as RtcpParseResult.Parsed).feedback
    T.assertEquals(
        "kAllPacketsLost ignores the bit vector",
        listOf(PacketNack(9, RtpDefines.ALL_PACKETS_LOST)),
        allLostFb.nacks,
    )

    // Feedback with no CST2 at all must parse: many receivers omit it.
    val noCst2 = buildCastFeedback(lossFields = listOf(Triple(6, 0, 0)), cst2 = null)
    val noCst2Fb = CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(noCst2, maxFeedbackFrameId = 10)
    T.check("feedback without CST2 parses", noCst2Fb is RtcpParseResult.Parsed)
    T.assertEquals(
        "no frames acked without CST2",
        emptyList<Int>(),
        (noCst2Fb as RtcpParseResult.Parsed).feedback.receivedFrames,
    )

    // Mismatched SSRCs: silently ignore the packet, do not fail the datagram.
    val otherSender = buildCastFeedback(senderSsrc = 111L)
    T.check(
        "feedback for another SSRC is ignored, not rejected",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(otherSender, maxFeedbackFrameId = 10)
            .let { it is RtcpParseResult.Parsed && it.feedback.checkpointFrameId == null },
    )

    // A wrong identifier word IS a parse failure and discards the whole datagram.
    val badIdentifier = buildCastFeedback(identifier = 0x44414544L)
    T.check(
        "a non-'CAST' identifier is a parse failure",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(badIdentifier, maxFeedbackFrameId = 10)
            is RtcpParseResult.Malformed,
    )

    T.section("Compound RTCP datagrams")

    // A realistic datagram: reference time + receiver report + Cast feedback, concatenated.
    val compound = buildReceiverReferenceTime(0x0192a3b4c5d6e7f8UL.toLong()) +
        buildReceiverReport(lastStatusReportId = 0xa3b4c5d6L, delaySinceLastReportUnits = 65536) +
        buildCastFeedback(checkpoint = 4, lossFields = listOf(Triple(6, 1, 0)))
    val compoundParser = CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC)
    val compoundResult = compoundParser.parse(compound, maxFeedbackFrameId = 8)
    T.check(
        "compound datagram parses",
        compoundResult is RtcpParseResult.Parsed,
        (compoundResult as? RtcpParseResult.Malformed)?.reason ?: "",
    )
    val cf = (compoundResult as RtcpParseResult.Parsed).feedback
    T.assertEquals("compound: reference time", 0x0192a3b4c5d6e7f8UL.toLong(), cf.receiverReferenceNtp)
    T.assertEquals("compound: receiver report status id", 0xa3b4c5d6L, cf.receiverReport?.lastStatusReportId)
    T.assertEquals("compound: delay since last report", 1000L, cf.receiverReport?.delaySinceLastReportMs())
    T.assertEquals("compound: checkpoint", 4, cf.checkpointFrameId)
    T.assertEquals("compound: nacks", listOf(PacketNack(6, 1)), cf.nacks)

    // An older reference time means the datagram was reordered: drop it entirely.
    val older = buildReceiverReferenceTime(0x0192a3b4c5d60000UL.toLong()) +
        buildCastFeedback(checkpoint = 7)
    T.check(
        "a reordered datagram is dropped",
        compoundParser.parse(older, maxFeedbackFrameId = 8) is RtcpParseResult.Stale,
    )
    val newer = buildReceiverReferenceTime(0x0192a3b4c5d6ffffUL.toLong()) +
        buildCastFeedback(checkpoint = 7)
    T.check(
        "a newer datagram is accepted",
        compoundParser.parse(newer, maxFeedbackFrameId = 8) is RtcpParseResult.Parsed,
    )

    T.section("RTCP malformed and tolerated input")

    T.check(
        "an empty datagram parses to nothing",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(ByteArray(0), maxFeedbackFrameId = 1)
            is RtcpParseResult.Parsed,
    )
    T.check(
        "a truncated header is malformed",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(byteArrayOf(0x80.toByte(), 0xC8.toByte()), maxFeedbackFrameId = 1)
            is RtcpParseResult.Malformed,
    )
    T.check(
        "a bad version is malformed",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC)
            .parse(byteArrayOf(0x00, 0xC8.toByte(), 0x00, 0x00), maxFeedbackFrameId = 1)
            is RtcpParseResult.Malformed,
    )
    T.check(
        "an unknown packet type is malformed",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC)
            .parse(byteArrayOf(0x80.toByte(), 0x63, 0x00, 0x00), maxFeedbackFrameId = 1)
            is RtcpParseResult.Malformed,
    )
    T.check(
        "a truncated payload is malformed",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC)
            .parse(byteArrayOf(0x80.toByte(), 0xC8.toByte(), 0x00, 0x06), maxFeedbackFrameId = 1)
            is RtcpParseResult.Malformed,
    )

    // Some receivers send 'TIME' sync requests on the application-defined type. libcast ignores
    // them without erroring, and so must we.
    val timeSync = ByteWriter(16).let { body ->
        body.u32(RECEIVER_SSRC)
        body.u32(RtcpDefines.TIME_SYNC_REQUEST_NAME)
        val payload = body.toByteArray()
        val out = ByteWriter(payload.size + 4)
        SenderReportBuilder.writeCommonHeader(out, RtcpDefines.SUBTYPE_RECEIVER_LOG, RtcpDefines.PT_APPLICATION_DEFINED, payload.size)
        out.bytes(payload)
        out.toByteArray()
    }
    T.check(
        "a 'TIME' sync request is ignored without error",
        CompoundRtcpParser(SENDER_SSRC, RECEIVER_SSRC).parse(timeSync, maxFeedbackFrameId = 1)
            is RtcpParseResult.Parsed,
    )

    T.section("Frame-id expansion")

    T.assertEquals("expandLessThanOrEqual within range", 5, FrameIdExpansion.expandLessThanOrEqual(10, 5))
    T.assertEquals("expandLessThanOrEqual equal", 10, FrameIdExpansion.expandLessThanOrEqual(10, 10))
    T.assertEquals("expandLessThanOrEqual wraps down", 0x01FE, FrameIdExpansion.expandLessThanOrEqual(0x0202, 0xFE))
    T.assertEquals("expandLessThanOrEqual never exceeds the reference", 0x0100, FrameIdExpansion.expandLessThanOrEqual(0x0105, 0x00))
    T.assertEquals("expandGreaterThan", 7, FrameIdExpansion.expandGreaterThan(5, 7))
    T.assertEquals("expandGreaterThan wraps up", 0x0102, FrameIdExpansion.expandGreaterThan(0x00FF, 0x02))
    T.assertEquals("expandNearest for a 32-bit RTP timestamp", 0x1_0000_0000L, FrameIdExpansion.expandNearest(0xFFFF_FF00L, 0L, 32))
    // ExpandedValueBase::max_distance_for_expansion() is numeric_limits<T>::max() / 2, i.e. one
    // less than half the span: a truncated value exactly half a span above the reference must
    // expand downwards, not upwards.
    T.assertEquals("expandNearest at the half-span boundary expands downwards", -128L, FrameIdExpansion.expandNearest(0L, 0x80L, 8))
    T.assertEquals("expandNearest just below the boundary expands upwards", 0x7FL, FrameIdExpansion.expandNearest(0L, 0x7FL, 8))
}
