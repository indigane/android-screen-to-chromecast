package app.castmirror.core

import app.castmirror.core.cast.channel.CastMessage
import app.castmirror.core.cast.channel.CastMessageFramer
import app.castmirror.core.cast.channel.Namespaces
import app.castmirror.core.cast.channel.PayloadType
import app.castmirror.core.cast.channel.responseType
import app.castmirror.core.util.ByteWriter
import app.castmirror.core.util.Bytes
import app.castmirror.core.util.Json

/**
 * Builds a `CastMessage` body the way real Chromecast firmware does: `payload_type = STRING`
 * (field 5 = 0) but the UTF-8 payload placed in **field 7** (the `payload_binary` slot), with no
 * field 6. Used to reproduce the device quirk that broke every request/response correlation.
 *
 * Shared (module-internal) so the correlation test in `EndToEndTest` can use it too.
 */
fun field7StringCastFrameBody(
    sourceId: String,
    destinationId: String,
    namespace: String,
    json: String,
    protocolVersion: Int = 3,
): ByteArray {
    val w = ByteWriter(256)
    fun varint(value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) { w.u8(b); return }
            w.u8(b or 0x80)
        }
    }
    fun lenField(field: Int, bytes: ByteArray) {
        w.u8((field shl 3) or 2) // tag: field, wire type 2
        varint(bytes.size)
        w.bytes(bytes)
    }
    w.u8((1 shl 3) or 0); varint(protocolVersion)                 // field 1: protocol_version
    lenField(2, sourceId.toByteArray(Charsets.UTF_8))             // field 2: source_id
    lenField(3, destinationId.toByteArray(Charsets.UTF_8))        // field 3: destination_id
    lenField(4, namespace.toByteArray(Charsets.UTF_8))            // field 4: namespace
    w.u8((5 shl 3) or 0); varint(0)                               // field 5: payload_type = STRING
    lenField(7, json.toByteArray(Charsets.UTF_8))                 // field 7: the JSON, in the binary slot
    return w.toByteArray()
}

/** `CastMessage` protobuf encoding/decoding and the 4-byte length framing. */
fun runProtobufTests() {
    T.section("CastMessage protobuf")

    // Hand-computed encoding. Fields in tag order, proto2 wire format:
    //   field 1 (protocol_version, varint)  tag 0x08, value 0x00 (CASTV2_1_0)
    //   field 2 (source_id, string "s")     tag 0x12, len 1, 's'
    //   field 3 (destination_id, "r")       tag 0x1A, len 1, 'r'
    //   field 4 (namespace, "ns")           tag 0x22, len 2, 'n','s'
    //   field 5 (payload_type, varint)      tag 0x28, value 0x00 (STRING)
    //   field 6 (payload_utf8, "{}")        tag 0x32, len 2, '{','}'
    val expected = byteArrayOf(
        0x08, 0x00,
        0x12, 0x01, 's'.code.toByte(),
        0x1A, 0x01, 'r'.code.toByte(),
        0x22, 0x02, 'n'.code.toByte(), 's'.code.toByte(),
        0x28, 0x00,
        0x32, 0x02, '{'.code.toByte(), '}'.code.toByte(),
    )
    val msg = CastMessage(sourceId = "s", destinationId = "r", namespace = "ns", payloadUtf8 = "{}")
    T.assertBytes("encode matches hand-computed bytes", expected, msg.encode())
    T.assertEquals("encoded length is 18", 18, msg.encode().size)

    T.assertEquals("decode round-trips", msg, CastMessage.decode(msg.encode()))

    // Framing: 4-byte big-endian length prefix, then the body.
    val framed = msg.encodeFramed()
    T.assertEquals("framed length prefix", 18L, Bytes.u32(framed, 0))
    T.assertEquals("framed total size", 22, framed.size)

    // Binary payload (the shape `deviceauth` uses).
    val binary = CastMessage(
        sourceId = Namespaces.PLATFORM_SENDER_ID,
        destinationId = Namespaces.PLATFORM_RECEIVER_ID,
        namespace = Namespaces.AUTH,
        payloadBinary = byteArrayOf(0x0A, 0x00),
    )
    T.assertEquals("binary payload sets payload_type=BINARY", PayloadType.BINARY, binary.payloadType)
    val decodedBinary = CastMessage.decode(binary.encode())
    T.check(
        "binary payload round-trips",
        decodedBinary.payloadBinary?.contentEquals(byteArrayOf(0x0A, 0x00)) == true,
    )
    T.assertEquals("binary payload type survives", PayloadType.BINARY, decodedBinary.payloadType)

    // A realistic message with long UTF-8 strings.
    val real = CastMessage(
        sourceId = "sender-8371",
        destinationId = "receiver-0",
        namespace = Namespaces.CONNECTION,
        payloadUtf8 = """{"type":"CONNECT","origin":{},"userAgent":"castmirror/1.0"}""",
    )
    T.assertEquals("realistic message round-trips", real, CastMessage.decode(real.encode()))

    // Unknown fields must be skipped, not rejected: field 8 (bool continued) and a fixed32.
    val withUnknown = msg.encode() +
        byteArrayOf(0x40, 0x01) +                                  // field 8, varint 1
        byteArrayOf(0x4D, 0x01, 0x02, 0x03, 0x04)                  // field 9, fixed32
    T.assertEquals("unknown fields are skipped", msg, CastMessage.decode(withUnknown))

    // Missing required fields must be rejected.
    T.assertThrows("missing source_id is rejected", "source_id") {
        CastMessage.decode(byteArrayOf(0x08, 0x00))
    }

    T.section("CastMessage decode: the receiver's field-7 STRING quirk")

    // These two frames are captured VERBATIM from a real Chromecast (via castprobe --raw). The
    // receiver sets payload_type=STRING (field 5 = 0) but puts the UTF-8 JSON in FIELD 7 (the
    // payload_binary slot) with no field 6. A field-number-driven decoder files the JSON under
    // payloadBinary, leaves payloadUtf8 null, and every requestId correlation silently times out
    // while the socket stays healthy — the exact "all requests time out, no error" device bug.

    // Frame 1: the CONNECTED response on tp.connection.
    val connectedFrame = Bytes.fromHex(
        "0803120a72656365697665722d301a0873656e6465722d30222875726e3a782d636173743a636f6d2e676f" +
            "6f676c652e636173742e74702e636f6e6e656374696f6e28003a287b2270726f746f636f6c56657273696f" +
            "6e223a332c2274797065223a22434f4e4e4543544544227d"
    )
    val connected = CastMessage.decode(connectedFrame)
    T.assertEquals("frame 1 namespace", "urn:x-cast:com.google.cast.tp.connection", connected.namespace)
    T.assertEquals("frame 1 payload_type is STRING", PayloadType.STRING, connected.payloadType)
    T.assertEquals(
        "frame 1 field-7 JSON is recovered as payloadUtf8",
        """{"protocolVersion":3,"type":"CONNECTED"}""",
        connected.payloadUtf8,
    )
    T.check("frame 1 payloadBinary is null", connected.payloadBinary == null)

    // Frame 2: the RECEIVER_STATUS reply, echoing requestId 1.
    val statusFrame = Bytes.fromHex(
        "0803120a72656365697665722d301a0873656e6465722d30222375726e3a782d636173743a636f6d2e676f" +
            "6f676c652e636173742e726563656976657228003aa3017b22726571756573744964223a312c2273746174" +
            "7573223a7b22757365724571223a7b7d2c22766f6c756d65223a7b22636f6e74726f6c54797065223a2261" +
            "7474656e756174696f6e222c226c6576656c223a312e302c226d75746564223a66616c73652c2273746570" +
            "496e74657276616c223a302e30353030303030303037343530353830367d7d2c2274797065223a22524543" +
            "45495645525f535441545553227d"
    )
    val status = CastMessage.decode(statusFrame)
    T.check("frame 2 payloadUtf8 is non-null", status.payloadUtf8 != null)
    T.assertEquals("frame 2 payload_type is STRING", PayloadType.STRING, status.payloadType)
    val statusJson = Json.parse(status.payloadUtf8!!).asObjOrNull()!!
    T.assertEquals("frame 2 requestId echoes 1", 1, statusJson["requestId"]?.asIntOrNull())
    T.assertEquals("frame 2 responseType is RECEIVER_STATUS", "RECEIVER_STATUS", statusJson.responseType())

    // A synthesized field-7 broadcast (requestId 0, dst "*"): decodes like the real frames, and
    // is what the correlation test feeds to prove the requestId-0 guard.
    val broadcast = CastMessage.decode(
        field7StringCastFrameBody(
            sourceId = "receiver-0",
            destinationId = "*",
            namespace = Namespaces.RECEIVER,
            json = """{"requestId":0,"type":"RECEIVER_STATUS","status":{"applications":[]}}""",
        )
    )
    T.assertEquals("field-7 broadcast decodes payloadUtf8", PayloadType.STRING, broadcast.payloadType)
    T.check("field-7 broadcast payloadUtf8 is non-null", broadcast.payloadUtf8 != null)
    T.assertEquals("field-7 broadcast destination is the wildcard", "*", broadcast.destinationId)

    // The standard field-6 layout must still decode identically (our own encode() uses field 6).
    // "realistic message round-trips" above already covers the happy path; assert the two layouts
    // agree so the quirk handling never diverges from the canonical one.
    val canonical = CastMessage(
        sourceId = "receiver-0",
        destinationId = "sender-0",
        namespace = "urn:x-cast:com.google.cast.tp.connection",
        payloadUtf8 = """{"protocolVersion":3,"type":"CONNECTED"}""",
    )
    T.assertEquals(
        "field-6 and field-7 layouts decode to the same payloadUtf8",
        canonical.payloadUtf8,
        CastMessage.decode(canonical.encode()).payloadUtf8,
    )
    T.assertEquals("field-6 layout still round-trips", canonical, CastMessage.decode(canonical.encode()))

    T.section("CastMessageFramer")

    val framer = CastMessageFramer()
    T.check("no message before any bytes", framer.next() == null)

    // Deliver the frame one byte at a time: TCP is a stream and may split anywhere.
    for (i in framed.indices) {
        framer.append(framed, i, 1)
        if (i < framed.size - 1) {
            if (framer.next() != null) { T.check("no premature message at byte $i", false); break }
        }
    }
    T.assertEquals("message assembled from single-byte reads", msg, framer.next())
    T.check("framer is drained", framer.next() == null)

    // Two messages in one read.
    val two = framed + framed
    val framer2 = CastMessageFramer()
    framer2.append(two, 0, two.size)
    T.assertEquals("first of two messages", msg, framer2.next())
    T.assertEquals("second of two messages", msg, framer2.next())
    T.check("nothing left after two", framer2.next() == null)

    // An oversize declared length is a protocol error: the channel must be closed.
    val oversize = ByteArray(8)
    Bytes.putU32(oversize, 0, (CastMessage.MAX_BODY_SIZE + 1).toLong())
    val framer3 = CastMessageFramer()
    framer3.append(oversize, 0, oversize.size)
    T.assertThrows("oversize body length is rejected", "illegal CastMessage body length") { framer3.next() }

    // A zero length is equally illegal.
    val zero = ByteArray(8)
    val framer4 = CastMessageFramer()
    framer4.append(zero, 0, zero.size)
    T.assertThrows("zero body length is rejected", "illegal CastMessage body length") { framer4.next() }

    T.assertThrows("encoding a body over 64 KiB is refused", "exceeds") {
        CastMessage(
            sourceId = "s",
            destinationId = "r",
            namespace = "ns",
            payloadUtf8 = "x".repeat(CastMessage.MAX_BODY_SIZE),
        ).encodeFramed()
    }
}
