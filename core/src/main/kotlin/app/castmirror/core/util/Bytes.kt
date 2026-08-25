package app.castmirror.core.util

/**
 * Big-endian byte helpers and a small append-only byte writer.
 *
 * Every wire format in Cast Streaming (protobuf length framing, RTP, RTCP, mDNS)
 * is big-endian, so these helpers are used pervasively.
 */
object Bytes {

    /** Writes the low 8 bits of [v] at [off]. */
    fun putU8(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
    }

    /** Writes [v] as a big-endian unsigned 16-bit integer at [off]. */
    fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 1] = (v and 0xFF).toByte()
    }

    /** Writes [v] as a big-endian unsigned 32-bit integer at [off]. */
    fun putU32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = ((v ushr 24) and 0xFF).toByte()
        dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 3] = (v and 0xFF).toByte()
    }

    /** Writes [v] as a big-endian 64-bit integer at [off]. */
    fun putU64(dst: ByteArray, off: Int, v: Long) {
        putU32(dst, off, (v ushr 32) and 0xFFFFFFFFL)
        putU32(dst, off + 4, v and 0xFFFFFFFFL)
    }

    /** Reads the unsigned byte at [off]. */
    fun u8(src: ByteArray, off: Int): Int = src[off].toInt() and 0xFF

    /** Reads a big-endian unsigned 16-bit integer at [off]. */
    fun u16(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    /** Reads a big-endian unsigned 32-bit integer at [off] as a [Long] in `[0, 2^32)`. */
    fun u32(src: ByteArray, off: Int): Long =
        ((src[off].toLong() and 0xFF) shl 24) or
            ((src[off + 1].toLong() and 0xFF) shl 16) or
            ((src[off + 2].toLong() and 0xFF) shl 8) or
            (src[off + 3].toLong() and 0xFF)

    /** Reads a big-endian 64-bit integer at [off]. */
    fun u64(src: ByteArray, off: Int): Long = (u32(src, off) shl 32) or u32(src, off + 4)

    private val HEX = "0123456789abcdef".toCharArray()

    /** Lower-case hex encoding, matching openscreen's `HexEncode`. */
    fun toHex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * Decodes a hex string with no whitespace and an even number of digits.
     * Case-insensitive, matching libcast's `sscanf("%16llx")` behaviour.
     *
     * @throws IllegalArgumentException if the string is not valid hex.
     */
    fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex string must have an even length: ${s.length}" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = digit(s[i * 2])
            val lo = digit(s[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}

/** Append-only big-endian byte builder used by the RTP/RTCP/protobuf serializers. */
class ByteWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(8))
    /** Number of bytes written so far. */
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    /** Appends the low 8 bits of [v]. */
    fun u8(v: Int): ByteWriter {
        ensure(1); Bytes.putU8(buf, size, v); size += 1; return this
    }

    /** Appends [v] as a big-endian uint16. */
    fun u16(v: Int): ByteWriter {
        ensure(2); Bytes.putU16(buf, size, v); size += 2; return this
    }

    /** Appends [v] as a big-endian uint32. */
    fun u32(v: Long): ByteWriter {
        ensure(4); Bytes.putU32(buf, size, v); size += 4; return this
    }

    /** Appends [v] as a big-endian uint32. */
    fun u32(v: Int): ByteWriter = u32(v.toLong() and 0xFFFFFFFFL)

    /** Appends [v] as a big-endian uint64. */
    fun u64(v: Long): ByteWriter {
        ensure(8); Bytes.putU64(buf, size, v); size += 8; return this
    }

    /** Appends [count] bytes of [src] starting at [off]. */
    fun bytes(src: ByteArray, off: Int = 0, count: Int = src.size - off): ByteWriter {
        ensure(count); System.arraycopy(src, off, buf, size, count); size += count; return this
    }

    /** Overwrites the byte at absolute offset [at]. */
    fun patchU8(at: Int, v: Int) {
        Bytes.putU8(buf, at, v)
    }

    /** Overwrites the big-endian uint16 at absolute offset [at]. */
    fun patchU16(at: Int, v: Int) {
        Bytes.putU16(buf, at, v)
    }

    /** Returns a copy of everything written so far. */
    fun toByteArray(): ByteArray = buf.copyOf(size)
}
