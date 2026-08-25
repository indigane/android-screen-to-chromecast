package app.castmirror.core.cast.streaming

import kotlin.math.max
import kotlin.math.min

/**
 * Video bitrate controller, driven by the receiver's **real** RTCP congestion signals.
 *
 * ### Why this replaces the old drop-count controller
 *
 * The original controller (a port of GMS's `VideoBitrateSuggestor`) backed off whenever the local
 * `framesDropped`/`kickstartsSent` counters moved. On a real device that was a false signal: those
 * counters were dominated by the sender's own in-flight-budget pacing, not by network loss, so the
 * target ratcheted 6000 → 1000 kbps while the receiver reported **zero** loss the whole session —
 * producing a needlessly blocky picture. Now that video frames are never shed after encoding
 * (`SessionController.pump`), those local counters no longer reflect congestion at all.
 *
 * This controller instead consumes only what the receiver actually reports:
 *
 *  * **fraction lost** — the RTCP Receiver Report's "fraction lost" numerator (over 256). This is
 *    a direct measure of packets the receiver did not get.
 *  * **round-trip time** — the smoothed RTT. A sharply rising RTT that is also high in absolute
 *    terms indicates a filling bottleneck queue even before loss appears.
 *
 * With loss ≈ 0 and a low, steady RTT it ramps to the ceiling and holds there. It backs off only
 * on genuine sustained loss (or a real RTT blow-out), and ramps back on recovery. The asymmetry —
 * fast multiplicative decrease, gentle multiplicative increase — is the classic AIMD shape that
 * keeps it stable.
 *
 * @param maxBitrate the ceiling, in bits per second — the value offered as `maxBitRate`.
 * @param minBitrate the floor, in bits per second. Only reached under sustained real loss.
 */
class BitrateSuggestor(
    private val maxBitrate: Int,
    private val minBitrate: Int = DEFAULT_MIN_BITRATE,
) {
    private var lastRttMs = 0L

    /** The currently suggested bitrate in bits per second. Starts at the ceiling. */
    var current: Int = maxBitrate
        private set

    /**
     * Folds one RTCP feedback sample into the suggestion. Call on a fixed cadence (~once/second).
     *
     * @param fractionLostNumerator the receiver report's "fraction lost" numerator, over 256
     *   (0 means the receiver reports no loss).
     * @param rttMs the current smoothed round-trip time in milliseconds (0 = not yet measured).
     * @return the new suggestion when it changed, otherwise `null`.
     */
    fun onFeedback(fractionLostNumerator: Int, rttMs: Long): Int? {
        val lossPerMille = fractionLostNumerator * 1000 / 256
        // Only treat RTT as congestion when it is BOTH high in absolute terms AND climbing sharply,
        // so a merely-elevated-but-stable link does not throttle a loss-free stream.
        val rttBlowout = lastRttMs > 0 &&
            rttMs >= RTT_CONCERN_MS &&
            rttMs * 100 >= lastRttMs * RTT_RISE_PERCENT
        lastRttMs = rttMs

        val congested = lossPerMille >= BACKOFF_LOSS_PER_MILLE || rttBlowout
        val previous = current
        current = if (congested) {
            max(minBitrate, (current * BACK_OFF).toInt())
        } else {
            min(maxBitrate, (current * RAMP_UP).toInt())
        }
        return if (current == previous) null else current
    }

    /** Resets the suggestion to the ceiling, for a renegotiated session. */
    fun reset() {
        current = maxBitrate
        lastRttMs = 0L
    }

    companion object {
        /** Floor: 1 Mbps. Only reached under sustained real loss, never from local pacing alone. */
        const val DEFAULT_MIN_BITRATE = 1_000_000

        /**
         * Loss threshold that triggers a back-off, in parts per thousand. 20‰ = 2%: below this a
         * healthy link's occasional single lost packet must not throttle the stream.
         */
        const val BACKOFF_LOSS_PER_MILLE = 20

        /** RTT must exceed this (ms) before it can, on its own, be read as congestion. */
        const val RTT_CONCERN_MS = 250L

        /** …and be at least this percent of the previous sample (a sharp rise), i.e. 1.5×. */
        const val RTT_RISE_PERCENT = 150L

        private const val BACK_OFF = 0.7
        private const val RAMP_UP = 1.1
    }
}
