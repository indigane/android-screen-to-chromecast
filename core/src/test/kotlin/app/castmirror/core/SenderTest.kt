package app.castmirror.core

import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.FrameIds
import app.castmirror.core.cast.streaming.EnqueueResult
import app.castmirror.core.cast.streaming.Sender
import app.castmirror.core.cast.streaming.SenderPacketRouter
import app.castmirror.core.cast.streaming.rtcp.NtpTime
import app.castmirror.core.cast.streaming.rtcp.RtcpDefines
import app.castmirror.core.cast.streaming.rtcp.SenderReportBuilder
import app.castmirror.core.cast.streaming.rtp.RtpDefines
import app.castmirror.core.util.ByteWriter
import app.castmirror.core.util.Bytes

/** The 8-bit wire value of `FrameId::leader()`: "the receiver has acknowledged nothing yet". */
private const val LEADER_WIRE = 0xFF

private const val S_SSRC = 60_001L
private const val R_SSRC = 60_002L

/** Cast feedback packet builder parameterised for the sender tests. */
private fun feedback(
    checkpoint: Int,
    lossFields: List<Triple<Int, Int, Int>> = emptyList(),
    playoutDelayMs: Int = 400,
    pictureLoss: Boolean = false,
): ByteArray {
    val body = ByteWriter(64)
    body.u32(R_SSRC)
    body.u32(S_SSRC)
    body.u32(RtcpDefines.CAST_IDENTIFIER_WORD)
    body.u8(checkpoint)
    body.u8(lossFields.size)
    body.u16(playoutDelayMs)
    for ((f, p, b) in lossFields) { body.u8(f); body.u16(p); body.u8(b) }
    val payload = body.toByteArray()
    val out = ByteWriter(payload.size + 4)
    SenderReportBuilder.writeCommonHeader(
        out, RtcpDefines.SUBTYPE_FEEDBACK, RtcpDefines.PT_PAYLOAD_SPECIFIC, payload.size,
    )
    out.bytes(payload)
    val fb = out.toByteArray()
    if (!pictureLoss) return fb
    return fb + app.castmirror.core.cast.streaming.rtcp.buildPictureLossIndicator(R_SSRC, S_SSRC)
}

/** Receiver Report builder parameterised for the sender tests. */
private fun receiverReport(lastStatusReportId: Long, delayUnits: Long): ByteArray {
    val body = ByteWriter(32)
    body.u32(R_SSRC)
    body.u32(S_SSRC)
    body.u32(0L)
    body.u32(1000L)
    body.u32(0L)
    body.u32(lastStatusReportId)
    body.u32(delayUnits)
    val payload = body.toByteArray()
    val out = ByteWriter(payload.size + 4)
    SenderReportBuilder.writeCommonHeader(out, 1, RtcpDefines.PT_RECEIVER_REPORT, payload.size)
    out.bytes(payload)
    return out.toByteArray()
}

private class Clock(var nowMs: Long = 100_000)

private fun makeSender(clock: Clock, maxPacketSize: Int = 64): Sender = Sender(
    senderSsrc = S_SSRC,
    receiverSsrc = R_SSRC,
    rtpPayloadType = 100,
    rtpTimebase = StreamingConstants.VIDEO_TIMEBASE,
    aesKey = ByteArray(16) { it.toByte() },
    aesIvMask = ByteArray(16) { (16 - it).toByte() },
    targetPlayoutDelayMs = 400,
    maxPacketSize = maxPacketSize,
    clock = { clock.nowMs },
    wallClock = { 1_700_000_000_000L },
    initialSequenceNumber = 0,
)

private fun frame(id: Int, key: Boolean = false, rtpTs: Long = id.toLong(), size: Int = 100) = EncodedFrame(
    frameId = id,
    isKeyFrame = key,
    referencedFrameId = if (key) id else id - 1,
    rtpTimestamp = rtpTs,
    data = ByteArray(size) { ((it + id) and 0xFF).toByte() },
    captureTimeMs = 0,
)

/** Sender window, ACK/NACK, kickstart and RTT logic. */
fun runSenderTests() {
    T.section("Sender: frame-id base")

    // openscreen: FrameId::first() == FrameId(0) and FrameId::leader() == FrameId(-1)
    // (frame_id.h:104,116). SenderImpl initialises every frame-id tracker to leader()
    // (sender_impl.h:202-241), so GetNextFrameId() returns 0 for the very first frame.
    //
    // This is not cosmetic. openscreen's receiver starts with last_frame_consumed_ = leader() and
    // then waits for last_frame_consumed_ + 1 == frame 0; with allow_skip_to_keyframe defaulting
    // to false (session_config.h:72) it will not skip ahead, so a stream that starts at frame 1
    // never plays at all.
    T.assertEquals("FrameId::first() is 0", 0, FrameIds.FIRST)
    T.assertEquals("FrameId::leader() is -1", -1, FrameIds.LEADER)

    val c0 = Clock()
    val s0 = makeSender(c0, maxPacketSize = 1472)
    T.assertEquals("a fresh sender asks for frame 0", FrameIds.FIRST, s0.nextFrameId())
    T.assertEquals("the initial checkpoint is the leader", FrameIds.LEADER, s0.stats().checkpointFrameId)
    T.assertEquals("the initial last-enqueued id is the leader", FrameIds.LEADER, s0.stats().lastEnqueuedFrameId)
    T.check("a key frame is needed before anything is enqueued", s0.needsKeyFrame())

    T.assertEquals("frame 0 is a legal frame id", EnqueueResult.OK, s0.enqueue(frame(0, key = true)))
    s0.nextRtcpPacket(c0.nowMs)
    val first0 = s0.nextRtpPacket(c0.nowMs)!!
    T.assertEquals("the wire frame id of the first frame is 0", 0, Bytes.u8(first0, 13))
    T.assertEquals("the first key frame references itself", 0, Bytes.u8(first0, 18))

    // The receiver's own "nothing received yet" checkpoint is leader(), which reaches the sender
    // as the wire byte 0xFF. It must expand to -1, not to 255, and must not advance anything.
    s0.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE), nowMs = c0.nowMs)
    T.assertEquals("a leader checkpoint leaves the sender at the leader", FrameIds.LEADER, s0.stats().checkpointFrameId)

    T.assertThrows("a negative frame id is rejected", "frame id must be >=") {
        EncodedFrame(frameId = -1, isKeyFrame = true, referencedFrameId = -1, rtpTimestamp = 0, data = ByteArray(0))
    }

    T.section("Sender: enqueue and packetize")

    val clock = Clock()
    val sender = makeSender(clock)
    T.assertEquals("the first frame id is FrameId::first() == 0", FrameIds.FIRST, sender.nextFrameId())
    T.assertEquals("enqueue accepts the first frame", EnqueueResult.OK, sender.enqueue(frame(0, key = true)))
    T.assertEquals("next frame id advances", 1, sender.nextFrameId())

    // A Sender Report must go out before the first RTP packet: the receiver cannot time media
    // playout without the lip-sync anchor, and cannot send a Receiver Report until it has one.
    val sr = sender.nextRtcpPacket(clock.nowMs)
    T.check("a Sender Report is available immediately after the first enqueue", sr != null)
    T.assertEquals("it is a Sender Report", RtcpDefines.PT_SENDER_REPORT, Bytes.u8(sr!!, 1))
    T.check("only one Sender Report per burst", sender.nextRtcpPacket(clock.nowMs) == null)

    // maxPacketSize 64 -> payload chunk 41 -> a 100-byte frame is 3 packets.
    val p0 = sender.nextRtpPacket(clock.nowMs)
    val p1 = sender.nextRtpPacket(clock.nowMs)
    val p2 = sender.nextRtpPacket(clock.nowMs)
    T.check("three packets for a 100-byte frame at a 41-byte chunk size", p0 != null && p1 != null && p2 != null)
    T.assertEquals("packet 0 frame id", 0, Bytes.u8(p0!!, 13))
    T.assertEquals("packet ids ascend", listOf(0, 1, 2), listOf(p0, p1!!, p2!!).map { Bytes.u16(it, 14) })
    T.assertEquals("max packet id is 2 on every packet", listOf(2, 2, 2), listOf(p0, p1, p2).map { Bytes.u16(it, 16) })
    T.assertEquals("sequence numbers ascend", listOf(0, 1, 2), listOf(p0, p1, p2).map { Bytes.u16(it, 2) })
    T.assertEquals("only the last packet has the marker bit", 0x80 or 100, Bytes.u8(p2, 1))

    T.check("nothing more to send immediately", sender.nextRtpPacket(clock.nowMs) == null)

    T.section("Sender: kickstart")

    // The receiver does not yet know frame 0 exists, so once everything has been sent once a
    // kickstart resend of the frame's LAST packet becomes due. The interval is
    // max(targetDelay/20, MIN_KICKSTART_INTERVAL_MS) = max(20, 100) = 100 ms with RTT still 0 —
    // the floor keeps kickstart from hair-triggering on a static screen (see the datamosh fix).
    clock.nowMs += 99
    T.check("no kickstart before the 100 ms floor elapses", sender.nextRtpPacket(clock.nowMs) == null)
    clock.nowMs += 1
    val kick = sender.nextRtpPacket(clock.nowMs)
    T.check("a kickstart packet is sent after the 100 ms floor", kick != null)
    T.assertEquals("the kickstart is the frame's last packet", 2, Bytes.u16(kick!!, 14))
    T.assertEquals("the kickstart gets a fresh sequence number", 3, Bytes.u16(kick, 2))
    T.assertEquals("kickstart is counted", 1L, sender.stats().kickstartsSent)

    // Once the checkpoint reaches the last enqueued frame the receiver knows everything, so
    // kickstarting stops and the frame's buffer is released.
    sender.handleRtcpPacket(feedback(checkpoint = 0), nowMs = clock.nowMs)
    T.assertEquals("checkpoint advanced", 0, sender.stats().checkpointFrameId)
    clock.nowMs += 1000
    T.check("no kickstart once the checkpoint catches up", sender.nextRtpPacket(clock.nowMs) == null)

    T.section("Sender: NACK retransmission and the RTT staleness filter")

    val c2 = Clock()
    val s2 = makeSender(c2)
    s2.enqueue(frame(0, key = true))
    val sr2 = s2.nextRtcpPacket(c2.nowMs)!!
    val reportId = NtpTime.toStatusReportId(Bytes.u64(sr2, 8))
    repeat(3) { s2.nextRtpPacket(c2.nowMs) }

    // With no RTT measured yet, every NACK is honoured.
    s2.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, lossFields = listOf(Triple(0, 1, 0))), nowMs = c2.nowMs)
    T.assertEquals("NACK flags one packet for retransmit", 1L, s2.stats().packetsRetransmitted)
    val retransmit = s2.nextRtpPacket(c2.nowMs)
    T.check("the retransmitted packet is produced", retransmit != null)
    T.assertEquals("it is packet 1 of frame 0", 1, Bytes.u16(retransmit!!, 14))
    T.assertEquals("retransmissions consume a new sequence number", 3, Bytes.u16(retransmit, 2))

    // Measure an RTT of 100 ms: the Receiver Report echoes the Sender Report's status id.
    c2.nowMs += 100
    s2.handleRtcpPacket(receiverReport(reportId, delayUnits = 0), nowMs = c2.nowMs)
    T.assertEquals("RTT measured from the RR", 100L, s2.roundTripTimeMs())

    // Re-send everything at t, then NACK at t+50: those packets are still presumed in flight
    // (50 ms < RTT), so the NACK is stale and must be ignored.
    s2.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, lossFields = listOf(Triple(0, RtpDefines.ALL_PACKETS_LOST, 0))), nowMs = c2.nowMs)
    while (s2.nextRtpPacket(c2.nowMs) != null) { /* drain */ }
    val beforeStale = s2.stats().packetsRetransmitted
    c2.nowMs += 50
    s2.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, lossFields = listOf(Triple(0, 0, 0))), nowMs = c2.nowMs)
    T.assertEquals("a NACK inside one RTT is ignored as stale", beforeStale, s2.stats().packetsRetransmitted)

    // The same NACK a full RTT later is honoured.
    c2.nowMs += 51
    s2.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, lossFields = listOf(Triple(0, 0, 0))), nowMs = c2.nowMs)
    T.assertEquals("the same NACK after one RTT is honoured", beforeStale + 1, s2.stats().packetsRetransmitted)

    // A NACK for a frame that was never sent must be skipped, not crash.
    val before = s2.stats().packetsRetransmitted
    s2.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, lossFields = listOf(Triple(99, 0, 0))), nowMs = c2.nowMs)
    T.assertEquals("a NACK for an unknown frame is skipped", before, s2.stats().packetsRetransmitted)

    // A NACK for a packet id beyond the frame's packet count is ignored.
    s2.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, lossFields = listOf(Triple(0, 50, 0))), nowMs = c2.nowMs)
    T.assertEquals("a NACK for a nonexistent packet is ignored", before, s2.stats().packetsRetransmitted)

    T.section("Sender: the NTP origin is fixed at construction")

    // openscreen builds one NtpTimeConverter per session from (monotonic now, wall now) and then
    // converts monotonic instants only (ntp_time.cc:22-38). Re-reading the wall clock per report
    // would let an Android NTP resync step the Sender Report timeline sideways mid-session, and
    // would decouple the report's NTP timestamp from the RTP timestamp derived alongside it.
    val cNtp = Clock()
    var wallMs = 1_700_000_000_000L
    val sNtp = Sender(
        senderSsrc = S_SSRC,
        receiverSsrc = R_SSRC,
        rtpPayloadType = 100,
        rtpTimebase = StreamingConstants.VIDEO_TIMEBASE,
        aesKey = ByteArray(16),
        aesIvMask = ByteArray(16),
        maxPacketSize = 1472,
        clock = { cNtp.nowMs },
        wallClock = { wallMs },
        initialSequenceNumber = 0,
    )
    sNtp.enqueue(frame(0, key = true, rtpTs = 0, size = 10))
    val srA = sNtp.nextRtcpPacket(cNtp.nowMs)!!
    val ntpA = Bytes.u64(srA, 8)

    // The wall clock jumps forward an hour while 500 monotonic ms elapse.
    wallMs += 3_600_000L
    cNtp.nowMs += 500
    val srB = sNtp.nextRtcpPacket(cNtp.nowMs)!!
    val ntpB = Bytes.u64(srB, 8)
    T.assertEquals(
        "the NTP timestamp advances by the monotonic delta, not the wall-clock step",
        500L,
        NtpTime.toUnixMillis(ntpB) - NtpTime.toUnixMillis(ntpA),
    )

    T.section("Sender: RTT smoothing")

    T.assertEquals("first sample is taken as-is", 40L, Sender.smoothRoundTripTime(0, 40))
    T.assertEquals("fast attack: half weight upwards", 60L, Sender.smoothRoundTripTime(40, 80))
    T.assertEquals("slow decay: one-eighth weight downwards", 37L, Sender.smoothRoundTripTime(40, 16))

    T.section("Sender: flow control")

    // The id-span limit: frameId - checkpoint may never exceed 120.
    val c3 = Clock()
    val s3 = makeSender(c3, maxPacketSize = 1472)
    for (i in 0 until StreamingConstants.MAX_UNACKED_FRAMES) {
        val r = s3.enqueue(frame(i, key = i == FrameIds.FIRST, rtpTs = i.toLong(), size = 10))
        if (r != EnqueueResult.OK) { T.check("frame $i accepted", false, "got $r"); break }
    }
    T.assertEquals("120 frames fit in the window", 120L, s3.stats().framesEnqueued)
    T.assertEquals(
        "the 121st frame is refused",
        EnqueueResult.REACHED_ID_SPAN_LIMIT,
        s3.enqueue(frame(StreamingConstants.MAX_UNACKED_FRAMES, rtpTs = 120, size = 10)),
    )
    T.assertEquals("the refusal is counted as a drop", 1L, s3.stats().framesDropped)

    // The in-flight media duration limit: with no RTT measured the budget is the 66 ms floor.
    val c4 = Clock()
    val s4 = makeSender(c4, maxPacketSize = 1472)
    T.assertEquals("first frame always fits", EnqueueResult.OK, s4.enqueue(frame(0, key = true, rtpTs = 0, size = 10)))
    T.assertEquals(
        "a frame 100 ms further on exceeds the in-flight budget",
        EnqueueResult.MAX_DURATION_IN_FLIGHT,
        s4.enqueue(frame(1, rtpTs = 9_000, size = 10)),
    )

    T.section("Sender: canEnqueue is the pre-check the video pump backpressures on")

    // The pump asks canEnqueue() BEFORE pulling an encoded frame, so it never has to shed one.
    // canEnqueue must mirror enqueue's two pacing limits exactly, without mutating or counting.
    val c7 = Clock()
    val s7 = makeSender(c7, maxPacketSize = 1472)
    s7.enqueue(frame(0, key = true, rtpTs = 0, size = 10))
    T.check("a frame inside the in-flight budget is acceptable", s7.canEnqueue(rtpTimestamp = 1_000))
    T.check("a frame beyond the in-flight budget is refused", !s7.canEnqueue(rtpTimestamp = 9_000))
    val dropsBefore = s7.stats().framesDropped
    repeat(5) { s7.canEnqueue(rtpTimestamp = 9_000) }
    T.assertEquals("canEnqueue never counts a drop (it is a pure predicate)", dropsBefore, s7.stats().framesDropped)
    // canEnqueue agrees with what enqueue would actually do.
    T.assertEquals(
        "when canEnqueue is false, enqueue would indeed drop",
        EnqueueResult.MAX_DURATION_IN_FLIGHT,
        s7.enqueue(frame(1, rtpTs = 9_000, size = 10)),
    )
    // Once the checkpoint advances (an ack), the budget frees and canEnqueue turns true again —
    // exactly what lets the held frame through instead of shedding it.
    s7.enqueue(frame(1, rtpTs = 3_000, size = 10)) // now within budget (oldest is frame 0)
    s7.handleRtcpPacket(feedback(checkpoint = 1), nowMs = c7.nowMs)
    T.check("after the checkpoint catches up, a farther frame is acceptable again", s7.canEnqueue(rtpTimestamp = 9_000))

    T.section("Sender: key-frame requests")

    val c5 = Clock()
    val s5 = makeSender(c5, maxPacketSize = 1472)
    s5.enqueue(frame(0, key = true, rtpTs = 1, size = 10))
    T.check("no key frame needed initially", !s5.needsKeyFrame())

    // A key frame is already in flight, so the PLI must be ignored: the receiver keeps sending
    // PLIs until one lands, and forcing another IDR per PLI would be ruinous.
    s5.handleRtcpPacket(feedback(checkpoint = LEADER_WIRE, pictureLoss = true), nowMs = c5.nowMs)
    T.check("a PLI while a key frame is in flight is ignored", !s5.needsKeyFrame())

    // Once that key frame is acknowledged, a PLI does force a new one.
    s5.handleRtcpPacket(feedback(checkpoint = 0, pictureLoss = true), nowMs = c5.nowMs)
    T.check("a PLI after the key frame was received sets the flag", s5.needsKeyFrame())

    s5.enqueue(frame(1, key = true, rtpTs = 2, size = 10))
    T.check("enqueuing a key frame clears the flag", !s5.needsKeyFrame())

    T.section("Sender: CST2 out-of-order frame ACKs")

    val c6 = Clock()
    val s6 = makeSender(c6, maxPacketSize = 1472)
    for (i in 0..3) s6.enqueue(frame(i, key = i == FrameIds.FIRST, rtpTs = i.toLong() + 1, size = 10))
    while (s6.nextRtpPacket(c6.nowMs) != null) { /* drain */ }
    // Checkpoint 0 plus a CST2 vector marking frame 2 (= checkpoint + 2) received.
    val withCst2 = ByteWriter(64).let { body ->
        body.u32(R_SSRC); body.u32(S_SSRC); body.u32(RtcpDefines.CAST_IDENTIFIER_WORD)
        body.u8(0); body.u8(0); body.u16(400)
        body.u32(RtcpDefines.CST2_IDENTIFIER_WORD); body.u8(0); body.u8(2); body.u8(0x01); body.u8(0x00)
        val payload = body.toByteArray()
        val out = ByteWriter(payload.size + 4)
        SenderReportBuilder.writeCommonHeader(out, RtcpDefines.SUBTYPE_FEEDBACK, RtcpDefines.PT_PAYLOAD_SPECIFIC, payload.size)
        out.bytes(payload)
        out.toByteArray()
    }
    val fb6 = s6.handleRtcpPacket(withCst2, nowMs = c6.nowMs)
    T.assertEquals("CST2 reports frame 2 received", listOf(2), fb6?.receivedFrames)
    T.assertEquals("checkpoint moved to 0", 0, s6.stats().checkpointFrameId)
    // Frame 2 is cancelled, so a NACK for it is now skipped while frame 1 still retransmits.
    val beforeCancel = s6.stats().packetsRetransmitted
    s6.handleRtcpPacket(feedback(checkpoint = 0, lossFields = listOf(Triple(2, 0, 0))), nowMs = c6.nowMs + 1000)
    T.assertEquals("an ACKed frame is no longer retransmittable", beforeCancel, s6.stats().packetsRetransmitted)
    s6.handleRtcpPacket(feedback(checkpoint = 0, lossFields = listOf(Triple(1, 0, 0))), nowMs = c6.nowMs + 1000)
    T.assertEquals("an un-ACKed frame still retransmits", beforeCancel + 1, s6.stats().packetsRetransmitted)

    T.section("SenderPacketRouter pacing arithmetic")

    T.assertEquals(
        "21 packets per 10 ms burst at the default ceiling",
        21,
        SenderPacketRouter.computeMaxPacketsPerBurst(StreamingConstants.MAX_BURST_BITRATE, 1472, 10),
    )
    T.assertEquals("the burst size is floored at 1", 1, SenderPacketRouter.computeMaxPacketsPerBurst(1000, 1472, 10))
    T.assertEquals(
        "24 << 20 is 25165824, not 24000000",
        25_165_824,
        StreamingConstants.MAX_BURST_BITRATE,
    )

    T.section("DatagramUdpTransport: the media socket is not connected")

    // openscreen binds its media socket and sends with an explicit remote endpoint
    // (environment.cc:35 and :118); it never connect()s. A connected socket would have the kernel
    // drop every datagram whose source is not exactly answer.ipaddr:answer.udpPort, so a receiver
    // that answers with one address and transmits RTCP from another (multi-homed, or sourcing
    // from a different port) would look exactly like a dead link.
    val loopback = java.net.InetAddress.getLoopbackAddress()
    val receiverSocket = java.net.DatagramSocket(0, loopback)
    // A *second* socket on a different port, standing in for a receiver that does not source its
    // RTCP from the port it advertised.
    val otherSocket = java.net.DatagramSocket(0, loopback)
    try {
        val transport = app.castmirror.core.cast.streaming.DatagramUdpTransport(
            host = loopback.hostAddress!!,
            port = receiverSocket.localPort,
        )
        try {
            transport.send(byteArrayOf(1, 2, 3, 4))
            val probe = java.net.DatagramPacket(ByteArray(64), 64)
            receiverSocket.soTimeout = 2000
            receiverSocket.receive(probe)
            T.assertEquals("the transport reaches the advertised receiver port", 4, probe.length)

            val reply = byteArrayOf(9, 8, 7, 6)
            otherSocket.send(
                java.net.DatagramPacket(reply, reply.size, loopback, transport.localPort)
            )
            val got = transport.receive(2000)
            T.check("RTCP from an unexpected source port still arrives", got != null)
            T.assertBytes(
                "and arrives intact",
                reply,
                got!!.data.copyOf(got.length),
            )
        } finally {
            transport.close()
        }
    } finally {
        receiverSocket.close()
        otherSocket.close()
    }

    T.section("SenderPacketRouter demultiplexing")

    val rtcp = feedback(checkpoint = 0)
    T.assertEquals("RTCP is routed by the report author SSRC", R_SSRC, SenderPacketRouter.authorSsrc(rtcp, rtcp.size))
    val rtp = ByteArray(20).also {
        it[0] = 0x80.toByte(); it[1] = 100
        Bytes.putU32(it, 8, S_SSRC)
    }
    T.assertEquals("RTP is routed by the SSRC at offset 8", S_SSRC, SenderPacketRouter.authorSsrc(rtp, rtp.size))
    T.check("a runt datagram has no routable SSRC", SenderPacketRouter.authorSsrc(ByteArray(4), 4) == null)
}
