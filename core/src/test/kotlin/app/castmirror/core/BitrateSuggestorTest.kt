package app.castmirror.core

import app.castmirror.core.cast.streaming.BitrateSuggestor

/** The RTCP-driven bitrate controller: holds at ceiling with no real loss; backs off on real loss. */
fun runBitrateSuggestorTests() {
    T.section("BitrateSuggestor: zero loss + low RTT holds at the ceiling")

    val ceiling = 6_000_000
    // A fraction-lost numerator that clears the 2% back-off threshold (13/256 ≈ 5%).
    val lossy = 13
    val cleanRtt = 30L

    run {
        val s = BitrateSuggestor(ceiling)
        T.assertEquals("starts at the ceiling", ceiling, s.current)
        // Replay the captured device pattern: loss=0 for a whole minute at a low, steady RTT.
        var everChanged = false
        repeat(60) { if (s.onFeedback(0, cleanRtt) != null) everChanged = true }
        T.check("no change is ever suggested with zero loss", !everChanged)
        T.assertEquals("stays pinned at the ceiling (never floors to 1000 kbps)", ceiling, s.current)
    }

    T.section("BitrateSuggestor: sustained real loss backs off")

    run {
        val s = BitrateSuggestor(ceiling)
        val first = s.onFeedback(lossy, cleanRtt)
        T.check("a lossy report lowers the target", first != null && first < ceiling)
        T.assertEquals("one back-off is ×0.7", (ceiling * 0.7).toInt(), s.current)
        // Sustained loss drives it down to, and clamps at, the 1 Mbps floor.
        repeat(30) { s.onFeedback(lossy, cleanRtt) }
        T.assertEquals("sustained loss clamps at the floor", BitrateSuggestor.DEFAULT_MIN_BITRATE, s.current)
    }

    T.section("BitrateSuggestor: recovery ramps back toward the ceiling")

    run {
        val s = BitrateSuggestor(ceiling)
        repeat(30) { s.onFeedback(lossy, cleanRtt) } // down to the floor
        T.assertEquals("at the floor after sustained loss", BitrateSuggestor.DEFAULT_MIN_BITRATE, s.current)
        val ramp = s.onFeedback(0, cleanRtt)
        T.check("recovery starts ramping up", ramp != null && ramp > BitrateSuggestor.DEFAULT_MIN_BITRATE)
        repeat(60) { s.onFeedback(0, cleanRtt) }
        T.assertEquals("recovery climbs all the way back to the ceiling", ceiling, s.current)
    }

    T.section("BitrateSuggestor: RTT is congestion only when high AND rising")

    run {
        // A stable, merely-elevated RTT must not throttle a loss-free stream.
        val s = BitrateSuggestor(ceiling)
        repeat(10) { s.onFeedback(0, 120) }
        T.assertEquals("a steady 120 ms RTT holds at the ceiling", ceiling, s.current)
    }
    run {
        // A sharp RTT blow-out (high absolute + a >1.5× jump) does back off, even with zero loss.
        val s = BitrateSuggestor(ceiling)
        s.onFeedback(0, 100)          // establish the baseline; still ceiling
        val backed = s.onFeedback(0, 300) // 300 ms >= 250 and >= 1.5 × 100
        T.check("a sharp RTT blow-out backs off", backed != null && backed < ceiling)
    }
}
