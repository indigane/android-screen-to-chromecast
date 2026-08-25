package app.castmirror.core

import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.EncryptedFrame
import app.castmirror.core.cast.streaming.rtp.RtpDefines
import app.castmirror.core.cast.streaming.rtp.RtpPacketizer
import app.castmirror.core.cast.streaming.rtp.RtpPayloadTypes
import app.castmirror.core.util.Bytes

/** RTP header layout, chunking and sequence numbering. */
fun runRtpTests() {
    T.section("RTP header layout")

    T.assertEquals("base header is 19 bytes (18 + reference frame id)", 19, RtpDefines.BASE_RTP_HEADER_SIZE)
    T.assertEquals("max header is 23 bytes", 23, RtpDefines.MAX_RTP_HEADER_SIZE)
    T.assertEquals("adaptive latency first word is 0x0402", 0x0402, RtpDefines.ADAPTIVE_LATENCY_FIRST_WORD)

    val packetizer = RtpPacketizer(
        payloadType = RtpPayloadTypes.VIDEO_H264,   // 101
        senderSsrc = 62811L,                        // 0x0000F55B
        maxPacketSize = StreamingConstants.MAX_RTP_PACKET_SIZE_IPV4,
        initialSequenceNumber = 0x1234,
    )
    T.assertEquals("IPv4 payload chunk size is 1449", 1449, packetizer.maxPayloadSize)

    // A 3000-byte key frame: ceil(3000/1449) == 3 packets of 1449, 1449, 102.
    val payload = ByteArray(3000) { (it and 0xFF).toByte() }
    val frame = EncodedFrame(
        frameId = 0x0102,              // low 8 bits = 0x02
        isKeyFrame = true,
        referencedFrameId = 0x0102,    // a key frame must reference itself
        rtpTimestamp = 0x00BB8000L,
        data = ByteArray(0),
    )
    val encrypted = EncryptedFrame(frame, payload)
    T.assertEquals("packet count for a 3000-byte frame", 3, packetizer.computeNumberOfPackets(encrypted))

    val p0 = packetizer.generatePacket(encrypted, 0)
    val expectedHeader0 = byteArrayOf(
        0x80.toByte(),                       // V=2, P=0, X=0, CC=0 -- always exactly 0x80
        0x65,                                // marker clear (not last) | PT 101
        0x12, 0x34,                          // sequence number
        0x00, 0xBB.toByte(), 0x80.toByte(), 0x00,  // RTP timestamp
        0x00, 0x00, 0xF5.toByte(), 0x5B,     // SSRC
        0xC0.toByte(),                       // key frame | has-reference-frame-id | ext count 0
        0x02,                                // frame id, low 8 bits
        0x00, 0x00,                          // packet id
        0x00, 0x02,                          // max packet id (num_packets - 1)
        0x02,                                // reference frame id, low 8 bits
    )
    T.assertBytes("packet 0 header matches the worked example", expectedHeader0, p0.copyOf(19))
    T.assertEquals("packet 0 total size", 19 + 1449, p0.size)
    T.assertBytes("packet 0 payload is bytes [0,1449)", payload.copyOfRange(0, 1449), p0.copyOfRange(19, p0.size))

    val p1 = packetizer.generatePacket(encrypted, 1)
    T.assertEquals("sequence number increments", 0x1235, Bytes.u16(p1, 2))
    T.assertEquals("packet 1 packet id", 1, Bytes.u16(p1, 14))
    T.assertEquals("packet 1 marker bit still clear", 0x65, Bytes.u8(p1, 1))
    T.assertBytes("packet 1 payload is bytes [1449,2898)", payload.copyOfRange(1449, 2898), p1.copyOfRange(19, p1.size))

    val p2 = packetizer.generatePacket(encrypted, 2)
    T.assertEquals("last packet sets the marker bit", 0xE5, Bytes.u8(p2, 1))
    T.assertEquals("packet 2 sequence number", 0x1236, Bytes.u16(p2, 2))
    T.assertEquals("packet 2 packet id", 2, Bytes.u16(p2, 14))
    T.assertEquals("packet 2 max packet id", 2, Bytes.u16(p2, 16))
    T.assertEquals("packet 2 size", 19 + (3000 - 2898), p2.size)
    T.assertBytes("packet 2 payload is the tail", payload.copyOfRange(2898, 3000), p2.copyOfRange(19, p2.size))

    T.check("all three packets carry the same RTP timestamp", Bytes.u32(p0, 4) == Bytes.u32(p2, 4))

    T.section("RTP key-frame and reference-frame bits")

    val delta = EncodedFrame(
        frameId = 0x0103,
        isKeyFrame = false,
        referencedFrameId = 0x0102,
        rtpTimestamp = 0x00BB8FA0L,
        data = ByteArray(0),
    )
    val deltaPacket = RtpPacketizer(100, 62812L, initialSequenceNumber = 0)
        .generatePacket(EncryptedFrame(delta, ByteArray(10)), 0)
    T.assertEquals(
        "delta frame clears the key-frame bit but keeps 0x40",
        RtpDefines.HAS_REFERENCE_FRAME_ID_BIT_MASK,
        Bytes.u8(deltaPacket, 12),
    )
    T.assertEquals("delta frame id low byte", 0x03, Bytes.u8(deltaPacket, 13))
    T.assertEquals("reference frame id low byte", 0x02, Bytes.u8(deltaPacket, 18))
    T.assertEquals("single-packet frame sets the marker bit", 0x80 or 100, Bytes.u8(deltaPacket, 1))
    T.assertEquals("single-packet frame has max packet id 0", 0, Bytes.u16(deltaPacket, 16))

    T.assertThrows("a key frame must reference itself", "reference itself") {
        EncodedFrame(frameId = 5, isKeyFrame = true, referencedFrameId = 4, rtpTimestamp = 1, data = ByteArray(0))
    }

    T.section("RTP adaptive-latency extension")

    val withDelay = EncodedFrame(
        frameId = 0x0104,
        isKeyFrame = false,
        referencedFrameId = 0x0103,
        rtpTimestamp = 0x00BB9000L,
        data = ByteArray(0),
        newPlayoutDelayMs = 500,
    )
    val pk = RtpPacketizer(100, 62812L, initialSequenceNumber = 0)
    val ext0 = pk.generatePacket(EncryptedFrame(withDelay, ByteArray(2000)), 0)
    T.assertEquals("extension count becomes 1", 0x41, Bytes.u8(ext0, 12))
    T.assertEquals("extension type/size word is 0x0402", 0x0402, Bytes.u16(ext0, 19))
    T.assertEquals("extension carries the new delay", 500, Bytes.u16(ext0, 21))
    T.assertEquals("packet 0 with extension is 23 bytes of header", 23 + 1449, ext0.size)

    val ext1 = pk.generatePacket(EncryptedFrame(withDelay, ByteArray(2000)), 1)
    T.assertEquals("only packet 0 carries the extension", 0x40, Bytes.u8(ext1, 12))
    T.assertEquals("packet 1 is 19 bytes of header", 19 + (2000 - 1449), ext1.size)

    T.section("RTP chunking edge cases")

    val tiny = RtpPacketizer(96, 100L, initialSequenceNumber = 0)
    T.assertEquals("a zero-byte frame still produces one packet", 1, tiny.computeNumberOfPackets(0))
    val silence = EncodedFrame(frameId = 1, isKeyFrame = true, referencedFrameId = 1, rtpTimestamp = 0, data = ByteArray(0))
    val silencePacket = tiny.generatePacket(EncryptedFrame(silence, ByteArray(0)), 0)
    T.assertEquals("zero-byte frame packet is header only", 19, silencePacket.size)

    T.assertEquals("exactly one chunk", 1, tiny.computeNumberOfPackets(1449))
    T.assertEquals("one byte over one chunk", 2, tiny.computeNumberOfPackets(1450))
    T.assertEquals("exactly two chunks", 2, tiny.computeNumberOfPackets(2898))

    // 65535 is reserved as the kAllPacketsLost sentinel, so 65535 packets is the limit.
    T.assertEquals(
        "a frame needing 65536 packets is refused",
        -1,
        tiny.computeNumberOfPackets(1449 * 65536),
    )

    T.section("RTP MTU configuration")

    val ipv6 = RtpPacketizer(100, 62812L, maxPacketSize = StreamingConstants.MAX_RTP_PACKET_SIZE_IPV6)
    T.assertEquals("IPv6 payload chunk size is 1429", 1429, ipv6.maxPayloadSize)
    val custom = RtpPacketizer(100, 62812L, maxPacketSize = 600)
    T.assertEquals("custom MTU chunk size", 577, custom.maxPayloadSize)
    T.assertThrows("MTU must exceed the header", "must exceed") { RtpPacketizer(100, 1L, maxPacketSize = 20) }
    T.assertThrows("payload type is validated", "invalid RTP payload type") { RtpPacketizer(50, 1L) }

    T.section("RTP payload type mapping")

    T.assertEquals("opus", 96, RtpPayloadTypes.forCodec("opus"))
    T.assertEquals("aac", 97, RtpPayloadTypes.forCodec("aac"))
    T.assertEquals("vp8", 100, RtpPayloadTypes.forCodec("vp8"))
    T.assertEquals("vp9 shares vp8's value", 100, RtpPayloadTypes.forCodec("vp9"))
    T.assertEquals("h264", 101, RtpPayloadTypes.forCodec("h264"))
    T.assertEquals("hevc shares h264's value", 101, RtpPayloadTypes.forCodec("hevc"))
    T.assertEquals("av1", 104, RtpPayloadTypes.forCodec("av1"))
    T.assertEquals("android-tv audio hack", 127, RtpPayloadTypes.forCodec("opus", androidTvHack = true))
    T.assertEquals("android-tv video hack", 96, RtpPayloadTypes.forCodec("h264", androidTvHack = true))
    T.assertThrows("unknown codec", "unknown codec") { RtpPayloadTypes.forCodec("theora") }
}
