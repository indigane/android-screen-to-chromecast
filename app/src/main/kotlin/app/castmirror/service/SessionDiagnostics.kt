package app.castmirror.service

import app.castmirror.core.cast.streaming.SenderStats
import app.castmirror.core.cast.streaming.rtcp.ReceiverReportBlock
import app.castmirror.core.cast.streaming.rtcp.RtcpFeedback
import app.castmirror.encode.AudioEncodeSnapshot
import app.castmirror.encode.EncodeSnapshot
import app.castmirror.util.AppLog

/**
 * Turns the core's sender/RTCP signals into a readable, throttled diagnostic log.
 *
 * It draws on three sources:
 *
 *  * the once-per-second [SenderStats] and [EncodeSnapshot] samples, combined into one STATS line;
 *  * each inbound RTCP compound, via `SenderPacketRouter.onFeedback` — receiver reports, Cast
 *    checkpoints, NACKs and PLIs;
 *  * the actual retransmission bursts, via `SenderObserver.onPacketsRetransmitted`.
 *
 * Everything is throttled so a lossy session cannot flood the log: per-event lines are rate limited
 * and per-second counts are aggregated. The receive-thread callbacks ([onFeedback],
 * [onRetransmitted]) and the stats-thread sampler ([logStatsTick]) touch shared counters, so the
 * mutating methods are synchronized on this instance.
 *
 * When [verbose] is false — the default — the log stays minimal: a terse fps/bitrate/loss line
 * every few seconds, plus a loud line whenever a frame is shed. Turning it on adds the dense
 * per-second STATS line and the per-event RTCP / retransmit detail, which is what the "Verbose
 * diagnostics" setting controls.
 */
class SessionDiagnostics(private val verbose: Boolean) {

    private var lastReceiverReport: ReceiverReportBlock? = null
    private var lastAudioReceiverReport: ReceiverReportBlock? = null

    /** SSRC of the audio stream, when audio is active; shown in the audio diagnostics line. */
    var audioSsrc: Long? = null

    private var prevAudioFrames = 0L
    private var prevAudioBytes = 0L

    private var nackPacketsThisSec = 0
    private var retransmitPacketsThisSec = 0
    private var pliThisSec = 0
    private val nackFrameIds = LinkedHashSet<Int>()

    private var lastNackLogMs = 0L
    private var lastRetransmitLogMs = 0L
    private var lastReceiverReportLogMs = 0L
    private var lastTerseLogMs = 0L
    private var lastAudioTerseLogMs = 0L

    private var prevFramesEmitted = 0L
    private var prevBytesEmitted = 0L
    private var prevFramesDropped = 0L

    /**
     * Records one inbound RTCP compound. Runs on the core's receive thread.
     *
     * Audio feedback contributes only its receiver report (for the audio loss figure); the
     * detailed NACK/PLI/retransmit logging is kept to the video stream to avoid doubling the log.
     *
     * @param isAudio whether the datagram was routed to the audio sender.
     * @param fb the parsed feedback the [Sender][app.castmirror.core.cast.streaming.Sender] just
     *   applied.
     */
    @Synchronized
    fun onFeedback(isAudio: Boolean, fb: RtcpFeedback) {
        if (isAudio) {
            fb.receiverReport?.let { lastAudioReceiverReport = it }
            return
        }
        val now = nowMs()

        fb.receiverReport?.let { report ->
            lastReceiverReport = report
            if (verbose && now - lastReceiverReportLogMs >= RECEIVER_REPORT_LOG_INTERVAL_MS) {
                lastReceiverReportLogMs = now
                val lossPercent = report.fractionLostNumerator * 100 / 256
                AppLog.i(
                    "rtcp RR: lost=${report.fractionLostNumerator}/256 (~$lossPercent%) " +
                        "cumulativeLost=${report.cumulativePacketsLost} jitter=${report.jitter}"
                )
            }
        }

        // The checkpoint is surfaced once per second in the STATS line (from SenderStats), so it
        // is not logged per compound here.

        if (fb.nacks.isNotEmpty()) {
            nackPacketsThisSec += fb.nacks.size
            fb.nacks.forEach { if (nackFrameIds.size < MAX_TRACKED_FRAME_IDS) nackFrameIds.add(it.frameId) }
            if (verbose && now - lastNackLogMs >= EVENT_LOG_INTERVAL_MS) {
                lastNackLogMs = now
                AppLog.i("rtcp NACK: ${fb.nacks.size} packet(s) for frames ${fb.nacks.map { it.frameId }.distinct()}")
            }
        }

        if (fb.pictureLossIndicator) {
            pliThisSec++
            if (verbose) AppLog.i("rtcp PLI: receiver decoder lost the picture and wants a key frame")
        }
    }

    /**
     * Records a retransmission burst. Runs on the core's receive thread.
     *
     * @param count packets flagged for resend by the last NACK.
     */
    @Synchronized
    fun onRetransmitted(count: Int) {
        if (count <= 0) return
        retransmitPacketsThisSec += count
        val now = nowMs()
        if (verbose && now - lastRetransmitLogMs >= EVENT_LOG_INTERVAL_MS) {
            lastRetransmitLogMs = now
            AppLog.i("retransmit: $count packet(s) resent")
        }
    }

    /**
     * Emits the once-per-second STATS line and resets the per-second aggregates.
     *
     * @param stats the video sender's cumulative counters.
     * @param encode the encoder's cumulative counters.
     * @param elapsedMs milliseconds since the previous call, for the rate figures.
     * @param targetBitrateBps the current [app.castmirror.core.cast.streaming.BitrateSuggestor] target, or `null`.
     * @return a compact line for the on-screen status, always populated.
     */
    @Synchronized
    fun logStatsTick(
        stats: SenderStats,
        encode: EncodeSnapshot,
        elapsedMs: Long,
        targetBitrateBps: Int?,
        audioStats: SenderStats? = null,
        audioEncode: AudioEncodeSnapshot? = null,
    ): String {
        val seconds = (elapsedMs.coerceAtLeast(1)).toDouble() / 1000.0
        val fps = ((encode.framesEmitted - prevFramesEmitted) / seconds).toInt().coerceAtLeast(0)
        val encodeKbps = (((encode.bytesEmitted - prevBytesEmitted) * 8).toDouble() / seconds / 1000.0).toInt()
        prevFramesEmitted = encode.framesEmitted
        prevBytesEmitted = encode.bytesEmitted

        val inFlight = (stats.lastEnqueuedFrameId - stats.checkpointFrameId).coerceAtLeast(0)
        val report = lastReceiverReport
        val lossPercent = report?.let { it.fractionLostNumerator * 100 / 256 }

        // `drop` now counts an ANOMALY, not routine pacing: video frames are never shed after
        // encoding, so any increase means an encoded frame could not be sent (e.g. too large) and
        // the sender forced a key-frame resync. Surface it loudly — a healthy session holds drop=0.
        val droppedThisSec = (stats.framesDropped - prevFramesDropped).coerceAtLeast(0)
        prevFramesDropped = stats.framesDropped
        if (droppedThisSec > 0) {
            AppLog.w("STATS anomaly: $droppedThisSec encoded frame(s) shed this second (reference chain broken; forced a key-frame resync)")
        }

        val uiLine = "$fps fps, ${encodeKbps} kbps enc, cp ${stats.checkpointFrameId}/${stats.lastEnqueuedFrameId}, " +
            "rtt ${stats.roundTripTimeMs} ms" + (lossPercent?.let { ", loss ~$it%" } ?: "")

        if (verbose) {
            AppLog.i(
                "STATS fps=$fps encKbps=$encodeKbps | " +
                    "sent=${stats.framesEnqueued} drop=${stats.framesDropped} ks=${stats.kickstartsSent} | " +
                    "pkts=${stats.packetsSent} rtx=${stats.packetsRetransmitted}(+$retransmitPacketsThisSec/s) | " +
                    "kf=${encode.keyFramesEmitted} kfReq=${encode.keyFrameRequestsIssued} | " +
                    "rtt=${stats.roundTripTimeMs}ms | " +
                    "loss=${report?.let { "${it.fractionLostNumerator}/256~${lossPercent}% cum=${it.cumulativePacketsLost} jit=${it.jitter}" } ?: "n/a"} | " +
                    "cp=${stats.checkpointFrameId}/${stats.lastEnqueuedFrameId} inflight=$inFlight | " +
                    "nack=${nackPacketsThisSec}/s pli=${pliThisSec}/s | " +
                    "tgt=${targetBitrateBps?.let { it / 1000 }?.toString() ?: "-"}kbps"
            )
            if (nackFrameIds.isNotEmpty()) {
                AppLog.i("STATS nack frames this second: ${nackFrameIds.toList()}")
            }
        } else {
            // Minimal always-on log: one terse line every few seconds, not every tick.
            val now = nowMs()
            if (now - lastTerseLogMs >= TERSE_LOG_INTERVAL_MS) {
                lastTerseLogMs = now
                AppLog.i("stats: $fps fps, $encodeKbps kbps" + (lossPercent?.let { ", loss ~$it%" } ?: ""))
            }
        }

        logAudioTick(audioStats, audioEncode, seconds)

        nackPacketsThisSec = 0
        retransmitPacketsThisSec = 0
        pliThisSec = 0
        nackFrameIds.clear()
        return uiLine
    }

    /**
     * Logs one audio line per stats tick when audio is active.
     *
     * Shows the audio encode rate and — from the audio stream's own receiver report — whether the
     * receiver is acking audio (checkpoint advancing, fraction lost), so a capture reveals whether
     * audio packets are getting through independently of video.
     */
    private fun logAudioTick(audioStats: SenderStats?, audioEncode: AudioEncodeSnapshot?, seconds: Double) {
        if (audioStats == null || audioEncode == null) return
        val aFps = ((audioEncode.framesEmitted - prevAudioFrames) / seconds).toInt().coerceAtLeast(0)
        val aKbps = (((audioEncode.bytesEmitted - prevAudioBytes) * 8).toDouble() / seconds / 1000.0).toInt()
        prevAudioFrames = audioEncode.framesEmitted
        prevAudioBytes = audioEncode.bytesEmitted
        val rr = lastAudioReceiverReport
        val lossPercent = rr?.let { it.fractionLostNumerator * 100 / 256 }

        if (verbose) {
            AppLog.i(
                "AUDIO fps=$aFps encKbps=$aKbps ssrc=${audioSsrc ?: "-"} | " +
                    "sent=${audioStats.framesEnqueued} cp=${audioStats.checkpointFrameId}/${audioStats.lastEnqueuedFrameId} " +
                    "rtt=${audioStats.roundTripTimeMs}ms | " +
                    "loss=${rr?.let { "${it.fractionLostNumerator}/256~${lossPercent}%" } ?: "n/a"}"
            )
        } else {
            val now = nowMs()
            if (now - lastAudioTerseLogMs >= TERSE_LOG_INTERVAL_MS) {
                lastAudioTerseLogMs = now
                AppLog.i("audio: $aFps fps, $aKbps kbps" + (lossPercent?.let { ", loss ~$it%" } ?: ""))
            }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val RECEIVER_REPORT_LOG_INTERVAL_MS = 450L
        const val EVENT_LOG_INTERVAL_MS = 250L
        const val TERSE_LOG_INTERVAL_MS = 5_000L
        const val MAX_TRACKED_FRAME_IDS = 16
    }
}
