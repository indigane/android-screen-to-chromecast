package app.castmirror.core.cast.streaming.rtcp

/**
 * Re-expansion of truncated sequence values
 * (`openscreen:cast/streaming/impl/expanded_value_base.h`).
 *
 * Cast puts only the **low 8 bits** of a 32-bit frame id on the wire, so the sender must
 * reconstruct the full id from context. openscreen's own comment is unusually candid about the
 * consequences:
 *
 * > "8 bits is not really enough: If a RTCP packet is received very late ... the Checkpoint Frame
 * > ID here will be mis-interpreted as representing a higher-numbered frame than what was
 * > intended. ... However, this design issue has been baked into the spec and millions of
 * > deployments over several years, and so there's no changing it now."
 *
 * The mitigation is `kMaxUnackedFrames = 120`, less than half the 8-bit range.
 */
object FrameIdExpansion {

    /**
     * The greatest value less than or equal to [reference] whose low 8 bits equal [truncated].
     *
     * Used for the Cast feedback checkpoint frame id, which can never exceed the highest frame
     * the sender has enqueued.
     */
    fun expandLessThanOrEqual(reference: Int, truncated: Int): Int {
        var result = (reference and 0xFFFFFF00.toInt()) or (truncated and 0xFF)
        if (result > reference) result -= 0x100
        return result
    }

    /**
     * The smallest value strictly greater than [reference] whose low 8 bits equal [truncated].
     *
     * Used for NACK "within frame id" fields, which are always above the checkpoint.
     */
    fun expandGreaterThan(reference: Int, truncated: Int): Int =
        expandLessThanOrEqual(reference + 0x100, truncated)

    /**
     * The value closest to [reference] whose low [bits] bits equal [truncated].
     *
     * Used for the 32-bit RTP timestamps in receiver event logs.
     */
    fun expandNearest(reference: Long, truncated: Long, bits: Int): Long {
        val span = 1L shl bits
        val mask = span - 1
        // `max_distance_for_expansion()` is `numeric_limits<ShortUnsigned>::max() / 2`, i.e.
        // `mask / 2` — one less than half the span, not half of it.
        val maxPossible = reference + mask / 2
        var result = (maxPossible and mask.inv()) or (truncated and mask)
        if (result > maxPossible) result -= span
        return result
    }
}
