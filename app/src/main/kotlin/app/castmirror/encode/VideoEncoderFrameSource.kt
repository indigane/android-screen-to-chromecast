package app.castmirror.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.FrameIds
import app.castmirror.core.cast.streaming.FrameSource
import app.castmirror.core.cast.streaming.RtpTime
import app.castmirror.util.AppLog
import java.nio.ByteBuffer

/** Why a key frame was asked for, for diagnostics. */
enum class KeyFrameReason {
    /** The stream has produced no key frame yet; this is the first one. */
    INITIAL,

    /** The receiver asked for one — a PLI arrived, or the core's checkpoint has not advanced. */
    RECEIVER,

    /** The screen rotated, so the whole frame is changing and a fresh IDR is cheaper than deltas. */
    ROTATION,
}

/**
 * A point-in-time reading of the encoder's cumulative counters, for the diagnostics STATS line.
 *
 * @property framesEmitted encoded frames handed on as [EncodedFrame]s.
 * @property keyFramesEmitted how many of those were key frames.
 * @property bytesEmitted total encoded payload bytes, for a measured-bitrate estimate.
 * @property keyFrameRequestsIssued `request-sync` calls actually made (after rate limiting).
 */
data class EncodeSnapshot(
    val framesEmitted: Long,
    val keyFramesEmitted: Long,
    val bytesEmitted: Long,
    val keyFrameRequestsIssued: Long,
)

/**
 * A [FrameSource] backed by a surface-input [MediaCodec] video encoder.
 *
 * The encoder's input surface is handed to a
 * [VirtualDisplay][android.hardware.display.VirtualDisplay] by
 * [ScreenCapture][app.castmirror.capture.ScreenCapture], so no pixels ever pass through this
 * process: the display server composites straight into the encoder.
 *
 * ### Driving model
 *
 * [nextFrame] is called from the core's `cast-pump-video` thread and drains the encoder's output
 * queue inline. That keeps the whole video path on one thread — no intermediate queue, no extra
 * copy, and no chance of a frame overtaking another — and it makes the frame-id contract trivial
 * to honour: the sender passes the id it wants, and the frame produced by this call carries it.
 *
 * ### Format
 *
 * The `MediaFormat` reproduces GMS's `VideoSource`, which is the configuration real Chromecast
 * firmware sees from every Android phone: CBR, no B-frames, a 60-second I-frame interval (Cast
 * re-syncs on PLI rather than on a cadence), `repeat-previous-frame-after` so a static screen
 * keeps the stream alive, and in-band SPS/PPS on every IDR. Two things GMS does not set are added
 * because they measurably help a low-latency mirror and are harmless when unsupported: `priority`
 * (realtime) and, on API 30+, `latency`.
 *
 * @param codecName the codec as named in the OFFER: `h264`, `vp8` or `vp9`.
 * @param width encode width in pixels; must be even.
 * @param height encode height in pixels; must be even.
 * @param initialBitRate target bitrate in bits per second.
 * @param frameRate target frame rate in frames per second.
 * @param keyFrameIntervalSeconds `i-frame-interval`.
 * @param rtpTimebase the negotiated stream's RTP timebase, 90000 for video.
 * @param errorRecoveryMode the OFFER's `errorRecoveryMode`, forwarded to the encoder as GMS does.
 */
class VideoEncoderFrameSource(
    private val codecName: String,
    val width: Int,
    val height: Int,
    initialBitRate: Int,
    private val frameRate: Int,
    private val keyFrameIntervalSeconds: Int,
    private val rtpTimebase: Int,
    private val errorRecoveryMode: String? = null,
) : FrameSource {

    private val mime = mimeFor(codecName)
    private val isH264 = mime == MediaFormat.MIMETYPE_VIDEO_AVC
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Guards the codec against concurrent `setParameters` / `close` from the service threads. */
    private val codecLock = Any()

    private val codec: MediaCodec
    private val configuration: Configuration

    /** The surface the virtual display must render into. */
    val inputSurface: Surface

    /** Cached `csd-0` + `csd-1`, prepended manually when the encoder ignored the prepend flag. */
    private var codecSpecificData: ByteArray? = null

    /** Set once an IDR is observed to be missing its SPS, from which point CSD is prepended by hand. */
    private var prependCsdManually = false

    private var currentBitRate = initialBitRate
    private var basePresentationUs = Long.MIN_VALUE
    private var lastRtpTimestamp = -1L
    /**
     * The id of the most recent key frame, or [FrameIds.LEADER] when none has been emitted yet.
     *
     * The sentinel must not be a usable frame id: the first frame of a stream is
     * [FrameIds.FIRST] == 0, so 0 cannot double as "no key frame yet".
     */
    private var lastKeyFrameId = FrameIds.LEADER
    private var lastKeyFrameRequestMs = 0L

    @Volatile
    private var framesEmitted = 0L

    @Volatile
    private var keyFramesEmitted = 0L

    @Volatile
    private var bytesEmitted = 0L

    @Volatile
    private var keyFrameRequestsIssued = 0L

    private var verifiedFirstKeyFrame = false

    @Volatile
    private var closed = false

    init {
        require(width > 0 && height > 0) { "invalid encode size ${width}x$height" }
        val (created, config) = configureWithFallbacks()
        codec = created
        configuration = config
        inputSurface = codec.createInputSurface()
        codec.start()
        AppLog.i(
            "encoder: $mime ${width}x$height @ ${frameRate}fps ${initialBitRate / 1000}kbps " +
                "(tier=${config.tier}, prepend-sps-pps=${config.prependHeaderRequested})"
        )
    }

    // ---- FrameSource ------------------------------------------------------------------------

    /**
     * Drains the encoder until a sendable frame appears or [timeoutMs] elapses.
     *
     * Buffers that must never reach the wire are consumed here rather than returned:
     * `BUFFER_FLAG_CODEC_CONFIG` (cached as CSD, then dropped — Cast has no config channel), any
     * delta frame before the first key frame (GMS drops these too, and a receiver cannot decode
     * them), and any frame whose RTP timestamp would not be strictly greater than its
     * predecessor's, which `MediaCodec` can produce on a completely static screen.
     */
    override fun nextFrame(timeoutMs: Long, nextFrameId: Int): EncodedFrame? {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (!closed) {
            val remainingUs = ((deadlineNs - System.nanoTime()) / 1_000L).coerceAtLeast(0L)
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, remainingUs)
            } catch (e: MediaCodec.CodecException) {
                // CodecException extends IllegalStateException, so it has to be caught first to
                // get at diagnosticInfo, which is the only useful part of a codec failure.
                if (closed) return null
                throw IllegalStateException("video encoder failed: ${e.diagnosticInfo}", e)
            } catch (e: IllegalStateException) {
                // Expected when close() released the codec under a blocked dequeue.
                if (closed) return null
                throw IllegalStateException("video encoder failed while dequeuing output", e)
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (System.nanoTime() >= deadlineNs) return null
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> cacheCsd(codec.outputFormat)

                index < 0 -> Unit

                else -> {
                    val frame = consumeOutputBuffer(index, nextFrameId)
                    if (frame != null) return frame
                    if (System.nanoTime() >= deadlineNs) return null
                }
            }
        }
        return null
    }

    /**
     * The core's key-frame request, from the pump thread.
     *
     * The core calls this on every pump iteration for as long as the sender needs a key frame,
     * which is either the very first frame or a PLI/checkpoint-stall recovery, so it is reported
     * as [KeyFrameReason.RECEIVER] (or [KeyFrameReason.INITIAL] before the first key frame).
     */
    override fun requestKeyFrame() = requestKeyFrame(KeyFrameReason.RECEIVER)

    /**
     * Asks the encoder for an IDR, at most once per second, recording why.
     *
     * Rate limiting is not optional — the pump calls the receiver path ~10 times a second while a
     * key frame is outstanding, and without the limit the encoder would emit nothing but key
     * frames. GMS uses the same one-per-second limit. A request that is actually issued is logged
     * and counted; a rate-limited one is dropped silently.
     *
     * @param reason the trigger, for the diagnostics log. [KeyFrameReason.RECEIVER] is reported as
     *   [KeyFrameReason.INITIAL] until the first key frame has been emitted.
     */
    fun requestKeyFrame(reason: KeyFrameReason) {
        val now = System.nanoTime() / 1_000_000L
        synchronized(codecLock) {
            if (closed) return
            if (now - lastKeyFrameRequestMs < KEY_FRAME_REQUEST_INTERVAL_MS) return
            lastKeyFrameRequestMs = now
            keyFrameRequestsIssued++
            val effective = if (reason == KeyFrameReason.RECEIVER && lastKeyFrameId == FrameIds.LEADER) {
                KeyFrameReason.INITIAL
            } else {
                reason
            }
            AppLog.i("encoder: key-frame request issued (${effective.name.lowercase()})")
            runCatching {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }.onFailure { AppLog.w("encoder: request-sync failed", it) }
        }
    }

    /** A snapshot of the cumulative counters, for the diagnostics STATS line. */
    fun snapshot(): EncodeSnapshot =
        EncodeSnapshot(framesEmitted, keyFramesEmitted, bytesEmitted, keyFrameRequestsIssued)

    /**
     * Retargets the encoder's bitrate.
     *
     * The core never calls this itself — it exposes congestion through `SenderStats` instead — so
     * [app.castmirror.service.MirrorService] drives it from a GMS-style [app.castmirror.core.cast.streaming.BitrateSuggestor].
     */
    override fun requestBitrate(bitsPerSecond: Int) {
        val clamped = bitsPerSecond.coerceAtLeast(MIN_BITRATE_BPS)
        if (clamped == currentBitRate) return
        currentBitRate = clamped
        setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped) })
    }

    /**
     * Stops and releases the encoder. Idempotent.
     *
     * The core closes frame sources from its teardown path while the pump thread may still be
     * blocked inside [nextFrame], so [closed] is set first: the pump either sees it at the top of
     * its loop or catches the `IllegalStateException` the release provokes and returns `null`.
     */
    override fun close() {
        if (closed) return
        closed = true
        synchronized(codecLock) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        runCatching { inputSurface.release() }
        AppLog.i("encoder: released after $framesEmitted frame(s)")
    }

    // ---- output handling --------------------------------------------------------------------

    /**
     * Turns one dequeued output buffer into a frame, or consumes it and returns `null`.
     *
     * @param index the buffer index from `dequeueOutputBuffer`.
     * @param nextFrameId the id the sender requires on the frame produced.
     */
    private fun consumeOutputBuffer(index: Int, nextFrameId: Int): EncodedFrame? {
        val flags = bufferInfo.flags
        val size = bufferInfo.size

        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (size > 0) codecSpecificData = readBuffer(index)
            releaseOutputBuffer(index)
            return null
        }
        if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            releaseOutputBuffer(index)
            AppLog.i("encoder: end of stream")
            closed = true
            return null
        }
        if (size <= 0) {
            releaseOutputBuffer(index)
            return null
        }

        val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (!isKeyFrame && lastKeyFrameId == FrameIds.LEADER) {
            // A receiver has nothing to decode against yet. GMS logs "Dropping non-keyframes."
            releaseOutputBuffer(index)
            return null
        }

        val presentationUs = bufferInfo.presentationTimeUs
        var payload = readBuffer(index)
        releaseOutputBuffer(index)

        if (isH264) payload = prepareH264(payload, isKeyFrame)

        if (basePresentationUs == Long.MIN_VALUE) basePresentationUs = presentationUs
        var rtpTimestamp = RtpTime.ticksFromMicros(presentationUs - basePresentationUs, rtpTimebase)
        if (rtpTimestamp <= lastRtpTimestamp) {
            // libcast CHECKs strict monotonicity. A dropped-because-equal timestamp used to be the
            // rule here, but discarding an already-encoded frame breaks the H.264 reference chain
            // (the next P-frame would reference content the receiver never got). This is a real
            // encoded frame, so nudge its timestamp one tick past the last instead of dropping it —
            // ~11 µs at 90 kHz, imperceptible — and keep the chain intact.
            rtpTimestamp = lastRtpTimestamp + 1
        }
        lastRtpTimestamp = rtpTimestamp
        if (isKeyFrame) {
            lastKeyFrameId = nextFrameId
            keyFramesEmitted++
        }
        framesEmitted++
        bytesEmitted += payload.size

        return EncodedFrame(
            frameId = nextFrameId,
            isKeyFrame = isKeyFrame,
            // A delta frame references its IMMEDIATE predecessor (frameId - 1), a key frame itself.
            // This is openscreen's own default and the truthful dependency for the linear, no-B-frame
            // P-frame chain this encoder produces. It also makes any missing frame VISIBLE to the
            // receiver's dependency tracking — so a real gap triggers a heal — rather than GMS's
            // "reference the last key frame", which only stays correct because GMS never drops a
            // frame and hides gaps behind renumbering (the datamosh mechanism seen on device). Since
            // this sender now transmits every encoded frame in order, the predecessor is always
            // present and decode is exact.
            referencedFrameId = if (isKeyFrame) nextFrameId else nextFrameId - 1,
            rtpTimestamp = rtpTimestamp,
            data = payload,
            captureTimeMs = presentationUs / 1000L,
        )
    }

    /**
     * Ensures an H.264 access unit is Annex-B and that an IDR carries its SPS/PPS in band.
     *
     * The first key frame is inspected explicitly: if `prepend-sps-pps-to-idr-frames` was accepted
     * at configure time but the encoder ignored it — which some vendor encoders do silently — the
     * absence of an SPS NAL is detected here and manual prepending is switched on for the rest of
     * the session.
     */
    private fun prepareH264(payload: ByteArray, isKeyFrame: Boolean): ByteArray {
        val annexB = AnnexB.toAnnexB(payload)
        if (!isKeyFrame) return annexB

        if (!verifiedFirstKeyFrame) {
            verifiedFirstKeyFrame = true
            if (!AnnexB.containsNalType(annexB, AnnexB.NAL_TYPE_SPS)) {
                prependCsdManually = true
                AppLog.w(
                    "encoder: the first IDR carried no SPS; prepending cached codec-specific data " +
                        "by hand for the rest of the session"
                )
            }
        }
        if (!prependCsdManually) return annexB

        val csd = codecSpecificData
        if (csd == null) {
            AppLog.w("encoder: no codec-specific data cached; the receiver may not be able to decode")
            return annexB
        }
        return AnnexB.prepend(AnnexB.toAnnexB(csd), annexB)
    }

    /** Caches `csd-0`/`csd-1` from the output format as one Annex-B blob. */
    private fun cacheCsd(format: MediaFormat) {
        val parts = ArrayList<ByteArray>(2)
        for (key in CSD_KEYS) {
            val buffer: ByteBuffer = runCatching { format.getByteBuffer(key) }.getOrNull() ?: continue
            val bytes = ByteArray(buffer.remaining())
            buffer.duplicate().get(bytes)
            if (bytes.isNotEmpty()) parts.add(bytes)
        }
        if (parts.isEmpty()) return
        var total = 0
        for (p in parts) total += p.size
        val blob = ByteArray(total)
        var w = 0
        for (p in parts) {
            System.arraycopy(p, 0, blob, w, p.size)
            w += p.size
        }
        codecSpecificData = blob
        AppLog.i("encoder: cached ${blob.size} bytes of codec-specific data")
    }

    private fun readBuffer(index: Int): ByteArray {
        val buffer = codec.getOutputBuffer(index)
            ?: throw IllegalStateException("encoder returned a null output buffer")
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val bytes = ByteArray(bufferInfo.size)
        buffer.get(bytes)
        return bytes
    }

    private fun releaseOutputBuffer(index: Int) {
        runCatching { codec.releaseOutputBuffer(index, false) }
    }

    private fun setParameters(params: Bundle) {
        if (closed) return
        synchronized(codecLock) {
            if (closed) return
            runCatching { codec.setParameters(params) }
                .onFailure { AppLog.w("encoder: setParameters failed", it) }
        }
    }

    // ---- configuration ----------------------------------------------------------------------

    /** What survived the configure fallbacks, for logging and for the CSD-prepend decision. */
    private class Configuration(val tier: String, val prependHeaderRequested: Boolean)

    /**
     * Configures the encoder, degrading the format until one is accepted.
     *
     * The tiers are ordered by how much is being asked for, and each drops the keys most likely to
     * be the cause:
     *
     *  1. everything — GMS's vendor keys, `profile`/`level` matching what the OFFER advertises,
     *     low-latency hints and the prepend flag;
     *  2. no vendor keys and no `profile`/`level`, which is exactly GMS's own format, the one
     *     known to work on every Android encoder Google ships against;
     *  3. additionally without `prepend-sps-pps-to-idr-frames`, the fallback GMS itself
     *     implements with a `try`/`catch` — CSD is then prepended by hand in [prepareH264].
     *
     * A failed `configure` leaves a `MediaCodec` unusable, so each tier gets a fresh instance.
     */
    private fun configureWithFallbacks(): Pair<MediaCodec, Configuration> {
        val tiers = listOf(
            Configuration("full", true),
            Configuration("gms", true),
            Configuration("minimal", false),
        )
        var lastError: Throwable? = null
        for (tier in tiers) {
            val created = try {
                MediaCodec.createEncoderByType(mime)
            } catch (e: Throwable) {
                throw IllegalStateException("no encoder available for $mime", e)
            }
            try {
                created.configure(buildFormat(tier), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return created to tier
            } catch (e: Throwable) {
                lastError = e
                runCatching { created.release() }
                AppLog.w("encoder: configure tier '${tier.tier}' rejected", e)
            }
        }
        throw IllegalStateException("could not configure a $mime encoder at ${width}x$height", lastError)
    }

    private fun buildFormat(tier: Configuration): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitRate)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSeconds)
        // Cast's frame model is a linear chain of referencedFrameId; out-of-order decode is not
        // representable, so B-frames are not merely undesirable but unsendable.
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        // A static screen produces no new frames at all. Repeating the last one keeps RTP flowing
        // so the receiver's 15-second media timeout never fires.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_PREVIOUS_FRAME_AFTER_US)
        format.setInteger(KEY_MAX_PTS_GAP_TO_ENCODER, MAX_PTS_GAP_US)
        format.setFloat(KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
        format.setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        if (tier.prependHeaderRequested && isH264) {
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        if (tier.tier == "full") {
            errorRecoveryMode?.takeIf { it.isNotEmpty() }
                ?.let { format.setString(KEY_ERROR_RECOVERY_MODE, it) }
            format.setInteger(KEY_USE_INTRA_MACROBLOCK_CYCLIC_MODE, 0)
            if (isH264) {
                // Advertised in the OFFER as profile "main" / level "4"; an encoder that cannot
                // deliver that drops to the next tier, which lets it pick its own default.
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
        }
        return format
    }

    companion object {
        /**
         * Maps an OFFER codec name to an Android MIME type, following GMS's `goxg.a`.
         *
         * @throws IllegalArgumentException for a codec this app cannot encode.
         */
        fun mimeFor(codecName: String): String = when (codecName.lowercase()) {
            "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "vp8" -> MediaFormat.MIMETYPE_VIDEO_VP8
            "vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> throw IllegalArgumentException("unsupported video codec '$codecName'")
        }

        /** GMS's rate limit on `request-sync`, in milliseconds. */
        const val KEY_FRAME_REQUEST_INTERVAL_MS = 1_000L

        /** GMS's `repeat-previous-frame-after`, in microseconds. */
        const val REPEAT_PREVIOUS_FRAME_AFTER_US = 70_000L

        /** GMS's `max-pts-gap-to-encoder`, in microseconds. */
        const val MAX_PTS_GAP_US = 33_333

        /** `MediaFormat` key GMS sets alongside the frame rate; no public constant exists. */
        const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"

        /**
         * `MediaFormat` key bounding how far apart two input timestamps may be before the encoder
         * treats the gap as a discontinuity. GMS sets it; there is no public constant.
         */
        const val KEY_MAX_PTS_GAP_TO_ENCODER = "max-pts-gap-to-encoder"

        /** Vendor key GMS forwards from the OFFER's `errorRecoveryMode`. */
        const val KEY_ERROR_RECOVERY_MODE = "error_recovery_mode"

        /** Vendor key GMS sets to 0 when cyclic intra refresh is disabled. */
        const val KEY_USE_INTRA_MACROBLOCK_CYCLIC_MODE = "use_intra_macroblock_cyclic_mode"

        /** `MediaFormat.KEY_PRIORITY` value meaning "realtime". */
        const val PRIORITY_REALTIME = 0

        /** Never ask an encoder for less than this; below it the picture is not worth sending. */
        const val MIN_BITRATE_BPS = 300_000

        private val CSD_KEYS = listOf("csd-0", "csd-1")
    }
}
