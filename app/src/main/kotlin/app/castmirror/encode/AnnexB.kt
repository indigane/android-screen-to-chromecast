package app.castmirror.encode

/**
 * H.264 Annex-B byte-stream helpers.
 *
 * Cast has no out-of-band codec-configuration channel: the receiver's decoder is fed the RTP
 * payload bytes and nothing else. That makes two properties mandatory for every H.264 frame put
 * on the wire, and this object is where both are checked.
 *
 *  1. **Start codes.** Frames must be Annex-B (`00 00 00 01` / `00 00 01` delimited), not the
 *     AVCC length-prefixed form. Android's `video/avc` encoders emit Annex-B, but the conversion
 *     is cheap and the failure mode of getting it wrong is a black screen with no error, so the
 *     form is verified rather than assumed.
 *  2. **In-band SPS/PPS on every IDR.** Verified with [containsNalType]; see
 *     [VideoEncoderFrameSource] for the fallback that prepends the cached codec-specific data
 *     when the encoder ignored `prepend-sps-pps-to-idr-frames`.
 */
object AnnexB {

    /** NAL unit type 5: a coded slice of an IDR picture. */
    const val NAL_TYPE_IDR = 5

    /** NAL unit type 7: sequence parameter set. */
    const val NAL_TYPE_SPS = 7

    /** NAL unit type 8: picture parameter set. */
    const val NAL_TYPE_PPS = 8

    /** The four-byte start code used when synthesising a byte stream. */
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** True when [data] begins with a three- or four-byte Annex-B start code. */
    fun hasStartCode(data: ByteArray, offset: Int = 0): Boolean {
        val n = data.size - offset
        if (n < 3) return false
        if (data[offset].toInt() != 0 || data[offset + 1].toInt() != 0) return false
        if (data[offset + 2].toInt() == 1) return true
        return n >= 4 && data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1
    }

    /**
     * True when [data] contains at least one NAL unit of type [nalType].
     *
     * Scans start codes rather than parsing the stream: enough to answer "did the encoder really
     * prepend an SPS to this IDR", which is the only question asked of it.
     */
    fun containsNalType(data: ByteArray, nalType: Int): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val headerLength = when {
                    data[i + 2].toInt() == 1 -> 3
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
                if (headerLength != 0) {
                    val headerIndex = i + headerLength
                    if (headerIndex < data.size && (data[headerIndex].toInt() and 0x1F) == nalType) return true
                    i += headerLength
                    continue
                }
            }
            i++
        }
        return false
    }

    /**
     * Returns [data] as an Annex-B byte stream, converting from AVCC if necessary.
     *
     * A buffer is treated as AVCC only when it parses cleanly as a sequence of four-byte
     * big-endian lengths that exactly covers it; anything else is returned untouched, because a
     * false positive here would corrupt every frame of the session.
     */
    fun toAnnexB(data: ByteArray): ByteArray {
        if (hasStartCode(data)) return data
        val units = parseLengthPrefixed(data) ?: return data
        var total = 0
        for (u in units) total += START_CODE.size + u.size
        val out = ByteArray(total)
        var w = 0
        for (u in units) {
            System.arraycopy(START_CODE, 0, out, w, START_CODE.size)
            w += START_CODE.size
            System.arraycopy(u, 0, out, w, u.size)
            w += u.size
        }
        return out
    }

    /**
     * Splits a four-byte-length-prefixed buffer into NAL units.
     *
     * @return the units, or `null` when [data] is not a well-formed AVCC buffer.
     */
    private fun parseLengthPrefixed(data: ByteArray): List<ByteArray>? {
        val units = ArrayList<ByteArray>(4)
        var i = 0
        while (i + 4 <= data.size) {
            val length = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            if (length <= 0 || i + 4 + length > data.size) return null
            units.add(data.copyOfRange(i + 4, i + 4 + length))
            i += 4 + length
        }
        return if (i == data.size && units.isNotEmpty()) units else null
    }

    /** Concatenates [prefix] and [frame] into one buffer. */
    fun prepend(prefix: ByteArray, frame: ByteArray): ByteArray {
        val out = ByteArray(prefix.size + frame.size)
        System.arraycopy(prefix, 0, out, 0, prefix.size)
        System.arraycopy(frame, 0, out, prefix.size, frame.size)
        return out
    }
}
