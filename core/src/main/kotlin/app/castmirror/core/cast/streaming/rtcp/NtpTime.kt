package app.castmirror.core.cast.streaming.rtcp

/**
 * NTP timestamp conversions (`openscreen:cast/streaming/impl/ntp_time.{h,cc}`).
 *
 * An NTP timestamp is a 64-bit value: the high 32 bits are seconds since 1 January 1900 and the
 * low 32 bits are a fraction of a second in units of 2^-32 s.
 */
object NtpTime {
    /** Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01). */
    const val EPOCH_OFFSET_SECONDS = 2_208_988_800L

    /** Converts Unix milliseconds to a 64-bit NTP timestamp. */
    fun fromUnixMillis(unixMillis: Long): Long {
        val seconds = Math.floorDiv(unixMillis, 1000L) + EPOCH_OFFSET_SECONDS
        val millisPart = Math.floorMod(unixMillis, 1000L)
        val fraction = (millisPart shl 32) / 1000L
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    /** Converts Unix nanoseconds to a 64-bit NTP timestamp, keeping sub-millisecond precision. */
    fun fromUnixNanos(unixNanos: Long): Long {
        val seconds = Math.floorDiv(unixNanos, 1_000_000_000L) + EPOCH_OFFSET_SECONDS
        val nanosPart = Math.floorMod(unixNanos, 1_000_000_000L)
        // fraction = nanos * 2^32 / 1e9, computed without overflowing.
        val fraction = (nanosPart shl 32) / 1_000_000_000L
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    /**
     * Converts a 64-bit NTP timestamp back to Unix milliseconds.
     *
     * The fraction is rounded rather than truncated, so that
     * `toUnixMillis(fromUnixMillis(t)) == t` for every millisecond value.
     */
    fun toUnixMillis(ntp: Long): Long {
        val seconds = (ntp ushr 32) - EPOCH_OFFSET_SECONDS
        val fraction = ntp and 0xFFFFFFFFL
        return seconds * 1000L + ((fraction * 1000L + (1L shl 31)) ushr 32)
    }

    /**
     * The "status report id": the **middle 32 bits** of an NTP timestamp.
     *
     * `toStatusReportId(0x0192a3b4c5d6e7f8) == 0xa3b4c5d6`. The receiver echoes this in the
     * Receiver Report's `last_status_report_id`, which is how the sender measures RTT.
     */
    fun toStatusReportId(ntp: Long): Long = (ntp ushr 16) and 0xFFFFFFFFL

    /**
     * Reconstructs the full NTP timestamp of a recently-sent sender report from its
     * [reportId], given an upper bound [onOrBeforeNtp].
     *
     * Takes the upper 16 bits of [onOrBeforeNtp], splices [reportId] into bits 16-47 and zeroes
     * the low 16; if that overshoots the bound, the low 48 bits must have rolled over, so
     * subtract 2^48. This is `SenderReportBuilder::GetRecentReportTime`.
     */
    fun recentReportNtp(reportId: Long, onOrBeforeNtp: Long): Long {
        var reconstructed = (onOrBeforeNtp and (0xFFFFL shl 48)) or ((reportId and 0xFFFFFFFFL) shl 16)
        if (java.lang.Long.compareUnsigned(reconstructed, onOrBeforeNtp) > 0) {
            reconstructed -= (1L shl 48)
        }
        return reconstructed
    }
}
