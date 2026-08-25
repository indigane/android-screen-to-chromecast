package app.castmirror.core.cast.channel

import app.castmirror.core.util.ByteWriter
import app.castmirror.core.util.Bytes

/**
 * `CastMessage.ProtocolVersion` from `cast_channel.proto`.
 *
 * Senders always emit [CASTV2_1_0]; the chunking versions are deprecated and unnecessary under
 * the 64 KiB body cap.
 */
enum class ProtocolVersion(val wire: Int) {
    /** No chunking. The value libcast sends (`kDefaultOutgoingMessageVersion`). */
    CASTV2_1_0(0),

    /** Message chunking support (deprecated). */
    CASTV2_1_1(1),

    /** Reworked message chunking. */
    CASTV2_1_2(2),

    /** Binary payload over utf8. */
    CASTV2_1_3(3);

    companion object {
        /** Maps a wire value to an enum constant, defaulting to [CASTV2_1_0] for unknown values. */
        fun fromWire(v: Int): ProtocolVersion = entries.firstOrNull { it.wire == v } ?: CASTV2_1_0
    }
}

/** `CastMessage.PayloadType` from `cast_channel.proto`. */
enum class PayloadType(val wire: Int) {
    /** JSON text in `payload_utf8`. Everything except `deviceauth`. */
    STRING(0),

    /** Raw protobuf bytes in `payload_binary`. Used only by `tp.deviceauth`. */
    BINARY(1);

    companion object {
        /** Maps a wire value to an enum constant, defaulting to [STRING]. */
        fun fromWire(v: Int): PayloadType = entries.firstOrNull { it.wire == v } ?: STRING
    }
}

/**
 * A Cast v2 control-channel message.
 *
 * Wire format (proto2, hand-rolled — no protobuf runtime dependency):
 *
 * | # | name | type |
 * |---|---|---|
 * | 1 | `protocol_version` | enum (varint) |
 * | 2 | `source_id` | string |
 * | 3 | `destination_id` | string |
 * | 4 | `namespace` | string |
 * | 5 | `payload_type` | enum (varint) |
 * | 6 | `payload_utf8` | string (optional) |
 * | 7 | `payload_binary` | bytes (optional) |
 *
 * Fields 8/9 (`continued`, `remaining_length`) belong to the deprecated chunking protocol and
 * are skipped on decode and never emitted.
 */
data class CastMessage(
    /** Sender-side endpoint id, e.g. `sender-0` or `sender-8371`. */
    val sourceId: String,
    /** Receiver-side endpoint id: `receiver-0`, an app `transportId`, or `*`. */
    val destinationId: String,
    /** Full namespace URN, e.g. `urn:x-cast:com.google.cast.receiver`. */
    val namespace: String,
    /** JSON payload, present iff [payloadType] is [PayloadType.STRING]. */
    val payloadUtf8: String? = null,
    /** Binary payload, present iff [payloadType] is [PayloadType.BINARY]. */
    val payloadBinary: ByteArray? = null,
    /** Payload discriminator. */
    val payloadType: PayloadType = if (payloadBinary != null) PayloadType.BINARY else PayloadType.STRING,
    /** Protocol version; always [ProtocolVersion.CASTV2_1_0] for outgoing messages. */
    val protocolVersion: ProtocolVersion = ProtocolVersion.CASTV2_1_0,
) {

    /** Serializes this message to its protobuf encoding (without the 4-byte length prefix). */
    fun encode(): ByteArray {
        val w = ByteWriter(128)
        writeVarintField(w, 1, protocolVersion.wire.toLong())
        writeStringField(w, 2, sourceId)
        writeStringField(w, 3, destinationId)
        writeStringField(w, 4, namespace)
        writeVarintField(w, 5, payloadType.wire.toLong())
        payloadUtf8?.let { writeStringField(w, 6, it) }
        payloadBinary?.let { writeBytesField(w, 7, it) }
        return w.toByteArray()
    }

    /**
     * Serializes this message with the mandatory 4-byte big-endian length prefix used on the
     * TLS control channel (`message_framer.cc`).
     *
     * @throws IllegalStateException if the body exceeds [MAX_BODY_SIZE].
     */
    fun encodeFramed(): ByteArray {
        val body = encode()
        check(body.isNotEmpty()) { "zero-length CastMessage bodies are invalid on send" }
        check(body.size <= MAX_BODY_SIZE) { "CastMessage body ${body.size} exceeds $MAX_BODY_SIZE" }
        val out = ByteArray(4 + body.size)
        Bytes.putU32(out, 0, body.size.toLong())
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CastMessage) return false
        return sourceId == other.sourceId &&
            destinationId == other.destinationId &&
            namespace == other.namespace &&
            payloadUtf8 == other.payloadUtf8 &&
            (payloadBinary?.contentEquals(other.payloadBinary) ?: (other.payloadBinary == null)) &&
            payloadType == other.payloadType &&
            protocolVersion == other.protocolVersion
    }

    override fun hashCode(): Int {
        var h = sourceId.hashCode()
        h = 31 * h + destinationId.hashCode()
        h = 31 * h + namespace.hashCode()
        h = 31 * h + (payloadUtf8?.hashCode() ?: 0)
        h = 31 * h + (payloadBinary?.contentHashCode() ?: 0)
        h = 31 * h + payloadType.hashCode()
        h = 31 * h + protocolVersion.hashCode()
        return h
    }

    override fun toString(): String =
        "CastMessage($sourceId -> $destinationId, ns=$namespace, " +
            (payloadUtf8 ?: "<${payloadBinary?.size ?: 0} binary bytes>") + ")"

    companion object {
        /** Cast's hard maximum message body size (`message_framer.cc:kMaxBodySize`). */
        const val MAX_BODY_SIZE = 65536

        /**
         * Decodes a protobuf-encoded `CastMessage` body (no length prefix).
         *
         * Unknown fields are skipped so that newer receivers cannot break the parser.
         *
         * ### Why the payload is resolved by `payload_type`, not by field number
         *
         * The proto defines field 6 (`payload_utf8`, string) and field 7 (`payload_binary`,
         * bytes), and the *intent* is that `payload_type` selects between them. Real Chromecast
         * firmware exploits that: it sends `payload_type = STRING` (field 5 = 0) but puts the
         * UTF-8 JSON in **field 7**, the `payload_binary` slot, and emits no field 6 at all —
         * captured verbatim from a device (see the CONNECT-response and RECEIVER_STATUS frames in
         * `ProtobufTest`). A field-number-driven decoder files that under `payloadBinary`, leaves
         * `payloadUtf8` null, and every JSON dispatch/`requestId` correlation silently fails while
         * the socket stays healthy — which is exactly the "all requests time out, no error"
         * symptom seen on the first device.
         *
         * So both length-delimited payload fields are captured raw, then resolved by
         * `payload_type` (defaulting to STRING when field 5 is absent): STRING takes the text from
         * whichever of field 6/7 is present, BINARY takes the bytes. This decodes the standard
         * layout, this receiver's field-7-for-STRING quirk, and deviceauth's field-7 BINARY, all
         * symmetrically. `encode()` is unchanged — we still emit field 6 for text, which every
         * receiver accepts.
         *
         * @throws IllegalArgumentException on malformed input or a missing required field.
         */
        fun decode(body: ByteArray, offset: Int = 0, length: Int = body.size - offset): CastMessage {
            val r = ProtoReader(body, offset, offset + length)
            var protocolVersion = ProtocolVersion.CASTV2_1_0
            var sourceId: String? = null
            var destinationId: String? = null
            var namespace: String? = null
            var payloadType: PayloadType? = null
            // Raw bytes of the two payload slots, held un-committed until payload_type resolves them.
            var f6utf8: ByteArray? = null
            var f7bin: ByteArray? = null

            while (!r.exhausted()) {
                val tag = r.readVarint()
                val field = (tag ushr 3).toInt()
                when (val wireType = (tag and 7L).toInt()) {
                    0 -> { // varint
                        val v = r.readVarint()
                        when (field) {
                            1 -> protocolVersion = ProtocolVersion.fromWire(v.toInt())
                            5 -> payloadType = PayloadType.fromWire(v.toInt())
                        }
                    }
                    2 -> { // length-delimited
                        val bytes = r.readLengthDelimited()
                        when (field) {
                            2 -> sourceId = String(bytes, Charsets.UTF_8)
                            3 -> destinationId = String(bytes, Charsets.UTF_8)
                            4 -> namespace = String(bytes, Charsets.UTF_8)
                            6 -> f6utf8 = bytes
                            7 -> f7bin = bytes
                        }
                    }
                    5 -> r.skip(4)
                    1 -> r.skip(8)
                    else -> throw IllegalArgumentException("unsupported protobuf wire type $wireType")
                }
            }

            require(sourceId != null) { "CastMessage missing required source_id" }
            require(destinationId != null) { "CastMessage missing required destination_id" }
            require(namespace != null) { "CastMessage missing required namespace" }

            // Default to STRING when payload_type is absent: that is proto2's default for the enum
            // and matches how text-only senders (including ours) leave the field implicit.
            val resolvedType = payloadType ?: PayloadType.STRING
            val payloadUtf8: String?
            val payloadBinary: ByteArray?
            when (resolvedType) {
                PayloadType.STRING -> {
                    payloadUtf8 = (f6utf8 ?: f7bin)?.let { String(it, Charsets.UTF_8) }
                    payloadBinary = null
                }
                PayloadType.BINARY -> {
                    payloadBinary = f7bin ?: f6utf8
                    payloadUtf8 = null
                }
            }

            return CastMessage(
                sourceId = sourceId,
                destinationId = destinationId,
                namespace = namespace,
                payloadUtf8 = payloadUtf8,
                payloadBinary = payloadBinary,
                payloadType = resolvedType,
                protocolVersion = protocolVersion,
            )
        }

        private fun writeTag(w: ByteWriter, field: Int, wireType: Int) {
            writeVarint(w, ((field.toLong() shl 3) or wireType.toLong()))
        }

        private fun writeVarint(w: ByteWriter, value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) { w.u8(b); return }
                w.u8(b or 0x80)
            }
        }

        private fun writeVarintField(w: ByteWriter, field: Int, value: Long) {
            writeTag(w, field, 0)
            writeVarint(w, value)
        }

        private fun writeBytesField(w: ByteWriter, field: Int, value: ByteArray) {
            writeTag(w, field, 2)
            writeVarint(w, value.size.toLong())
            w.bytes(value)
        }

        private fun writeStringField(w: ByteWriter, field: Int, value: String) =
            writeBytesField(w, field, value.toByteArray(Charsets.UTF_8))
    }
}

/** Minimal protobuf field reader used by [CastMessage.decode]. */
private class ProtoReader(val buf: ByteArray, var pos: Int, val end: Int) {
    fun exhausted(): Boolean = pos >= end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end) { "truncated varint" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        require(len >= 0 && pos + len <= end) { "truncated length-delimited field" }
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun skip(n: Int) {
        require(pos + n <= end) { "truncated fixed-width field" }
        pos += n
    }
}

/**
 * Incremental de-framer for the TLS control channel's 4-byte big-endian length prefix.
 *
 * TCP is a stream, so a read may deliver a partial message or several messages at once. Feed
 * every byte received into [append] and drain complete messages with [next].
 */
class CastMessageFramer {
    private var buf = ByteArray(8192)
    private var len = 0

    /**
     * Optional pre-decode hook: invoked with a **copy** of each complete frame's raw protobuf body
     * (the `bodyLen` slice, i.e. exactly the bytes at offset 4) *before* [CastMessage.decode] runs.
     *
     * `null` by default, so the decode path is completely unchanged in normal operation. It exists
     * for the capture tool, which needs the bytes exactly as the receiver sent them — decode is
     * lossy for anything it does not model, and a field it silently drops is precisely what a
     * capture is meant to reveal.
     */
    var onRawBody: ((ByteArray) -> Unit)? = null

    /** Appends [count] freshly-read bytes from [data] starting at [off]. */
    fun append(data: ByteArray, off: Int, count: Int) {
        if (len + count > buf.size) {
            var cap = buf.size
            while (cap < len + count) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(data, off, buf, len, count)
        len += count
    }

    /**
     * Returns the next complete message, or `null` when more bytes are needed.
     *
     * @throws IllegalStateException if a declared body length exceeds [CastMessage.MAX_BODY_SIZE],
     *   which is a protocol error and must close the socket.
     */
    fun next(): CastMessage? {
        if (len < 4) return null
        val bodyLen = Bytes.u32(buf, 0).toInt()
        check(bodyLen in 1..CastMessage.MAX_BODY_SIZE) {
            "illegal CastMessage body length $bodyLen; closing channel"
        }
        if (len < 4 + bodyLen) return null
        onRawBody?.let { it(buf.copyOfRange(4, 4 + bodyLen)) }
        val msg = CastMessage.decode(buf, 4, bodyLen)
        val remaining = len - (4 + bodyLen)
        System.arraycopy(buf, 4 + bodyLen, buf, 0, remaining)
        len = remaining
        return msg
    }
}
