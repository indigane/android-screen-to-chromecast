package app.castmirror.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.castmirror.capture.AudioPlaybackCapture
import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.FrameIds
import app.castmirror.core.cast.streaming.FrameSource
import app.castmirror.core.cast.streaming.RtpTime
import app.castmirror.util.AppLog

/** A point-in-time reading of the audio encoder's cumulative counters, for the diagnostics line. */
data class AudioEncodeSnapshot(
    val framesEmitted: Long,
    val bytesEmitted: Long,
)

/**
 * A [FrameSource] backed by a `MediaCodec` audio encoder fed from [AudioPlaybackCapture].
 *
 * One [EncodedFrame] is produced per audio access unit, following the Cast audio contract:
 * `isKeyFrame = true`, `referencedFrameId == frameId` (self-referential — every audio AU is
 * independently decodable), and an `rtpTimestamp` in 48 kHz ticks derived from the cumulative
 * sample count (not wall clock), strictly increasing by 1024 per AAC-LC AU (960 per 20 ms Opus
 * frame).
 *
 * ### Threading
 *
 * A dedicated reader thread pulls PCM from the capture and feeds the encoder's input buffers; the
 * core's `cast-pump-audio` thread calls [nextFrame], which drains one output AU. `MediaCodec`
 * tolerates input and output being serviced on different threads, and this keeps capture latency
 * off the pump.
 *
 * ### AAC framing
 *
 * Android's `audio/mp4a-latm` encoder emits raw AAC access units plus a `csd-0`
 * `BUFFER_FLAG_CODEC_CONFIG` buffer (the AudioSpecificConfig). Cast has no out-of-band config
 * channel, so `csd-0` is cached and **never sent**, and — when [adtsEnabled] — a 7-byte ADTS
 * header is prepended to each AU (AAC-LC, 48 kHz, stereo). If the encoder already emits ADTS (a
 * `0xFFF` syncword is present) the AU is passed through unchanged. With [adtsEnabled] false, raw
 * AUs are sent: the documented fallback for a receiver that plays ADTS-wrapped audio as silence.
 *
 * ### Opus
 *
 * `audio/opus` output is a raw Opus packet per AU with no framing; the `csd` buffers are likewise
 * discarded. Only usable where a `MediaCodec` Opus **encoder** exists ([hasEncoder]).
 *
 * @param capture the started PCM source; released by [close].
 * @param codecName the negotiated audio codec: `aac` or `opus`.
 * @param bitRate target bitrate in bits per second.
 * @param rtpTimebase the stream's RTP timebase (48000 for audio).
 * @param adtsEnabled whether to wrap AAC output in ADTS framing (ignored for Opus).
 */
class AudioEncoderFrameSource(
    private val capture: AudioPlaybackCapture,
    codecName: String,
    bitRate: Int,
    private val rtpTimebase: Int,
    private val adtsEnabled: Boolean,
) : FrameSource {

    private val mime = mimeFor(codecName)
    private val isAac = mime == MediaFormat.MIMETYPE_AUDIO_AAC
    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    private val bufferInfo = MediaCodec.BufferInfo()

    private val readerThread: Thread
    private val pcmBuffer = ByteArray(capture.bufferSizeBytes)

    private var basePresentationUs = Long.MIN_VALUE
    private var lastRtpTimestamp = -1L

    @Volatile private var framesEmitted = 0L
    @Volatile private var bytesEmitted = 0L
    @Volatile private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(mime, capture.sampleRate, capture.channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        if (isAac) {
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, capture.bufferSizeBytes)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        readerThread = Thread(::pumpPcm, "castmirror-audio-pcm").apply { isDaemon = true; start() }
        AppLog.i("audio-encoder: $mime ${capture.sampleRate}Hz ${capture.channelCount}ch ${bitRate / 1000}kbps adts=$adtsEnabled")
    }

    // ---- input: PCM -> encoder --------------------------------------------------------------

    /**
     * Continuously reads PCM from the capture and queues it into the encoder.
     *
     * The input presentation time is computed from the cumulative sample count, which is what makes
     * the resulting RTP timestamps sample-accurate and monotonic regardless of read scheduling.
     */
    private fun pumpPcm() {
        var totalSamples = 0L
        val bytesPerFrame = 2 * capture.channelCount
        while (!closed) {
            val read = try {
                capture.read(pcmBuffer)
            } catch (e: Throwable) {
                if (!closed) AppLog.w("audio-encoder: PCM read failed", e)
                return
            }
            if (read <= 0) continue
            var offset = 0
            var remaining = read
            while (remaining > 0 && !closed) {
                try {
                    val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (index < 0) continue
                    val input = codec.getInputBuffer(index) ?: continue
                    input.clear()
                    val chunk = minOf(remaining, input.capacity())
                    input.put(pcmBuffer, offset, chunk)
                    val ptsUs = totalSamples * 1_000_000L / capture.sampleRate
                    codec.queueInputBuffer(index, 0, chunk, ptsUs, 0)
                    totalSamples += chunk / bytesPerFrame
                    offset += chunk
                    remaining -= chunk
                } catch (e: IllegalStateException) {
                    // The codec was released by close() between operations; nothing more to feed.
                    if (closed) return
                    throw e
                }
            }
        }
    }

    // ---- output: encoder -> EncodedFrame ----------------------------------------------------

    override fun nextFrame(timeoutMs: Long, nextFrameId: Int): EncodedFrame? {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (!closed) {
            val remainingUs = ((deadlineNs - System.nanoTime()) / 1_000L).coerceAtLeast(0L)
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, remainingUs)
            } catch (e: MediaCodec.CodecException) {
                if (closed) return null
                throw IllegalStateException("audio encoder failed: ${e.diagnosticInfo}", e)
            } catch (e: IllegalStateException) {
                if (closed) return null
                throw IllegalStateException("audio encoder failed while dequeuing output", e)
            }

            if (index < 0) {
                if (System.nanoTime() >= deadlineNs) return null
                continue
            }

            val flags = bufferInfo.flags
            val size = bufferInfo.size
            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || size <= 0) {
                // csd (AudioSpecificConfig / Opus headers) is never sent on Cast; drop it.
                runCatching { codec.releaseOutputBuffer(index, false) }
                if (System.nanoTime() >= deadlineNs) return null
                continue
            }

            val presentationUs = bufferInfo.presentationTimeUs
            val raw = ByteArray(size)
            codec.getOutputBuffer(index)?.let { buf ->
                buf.position(bufferInfo.offset)
                buf.limit(bufferInfo.offset + size)
                buf.get(raw)
            }
            runCatching { codec.releaseOutputBuffer(index, false) }

            if (basePresentationUs == Long.MIN_VALUE) basePresentationUs = presentationUs
            val rtpTimestamp = RtpTime.ticksFromMicros(presentationUs - basePresentationUs, rtpTimebase)
            if (rtpTimestamp <= lastRtpTimestamp) continue
            lastRtpTimestamp = rtpTimestamp

            val payload = if (isAac) frameAac(raw) else raw
            framesEmitted++
            bytesEmitted += payload.size

            return EncodedFrame(
                frameId = nextFrameId,
                isKeyFrame = true,
                referencedFrameId = nextFrameId,
                rtpTimestamp = rtpTimestamp,
                data = payload,
                captureTimeMs = presentationUs / 1000L,
            )
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { readerThread.interrupt() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { capture.release() }
        AppLog.i("audio-encoder: released after $framesEmitted AU(s)")
    }

    /** A snapshot of the cumulative counters, for the diagnostics audio line. */
    fun snapshot(): AudioEncodeSnapshot = AudioEncodeSnapshot(framesEmitted, bytesEmitted)

    // ---- AAC ADTS framing -------------------------------------------------------------------

    private fun frameAac(au: ByteArray): ByteArray {
        if (!adtsEnabled) return au
        // Already ADTS? Pass through unchanged.
        if (au.size >= 2 && (au[0].toInt() and 0xFF) == 0xFF && (au[1].toInt() and 0xF0) == 0xF0) return au
        val out = ByteArray(ADTS_HEADER_LEN + au.size)
        writeAdtsHeader(out, au.size)
        System.arraycopy(au, 0, out, ADTS_HEADER_LEN, au.size)
        return out
    }

    /**
     * Writes a 7-byte ADTS header (no CRC) for one AAC-LC AU at 48 kHz, stereo.
     *
     * profile=AAC-LC (object type 2, ADTS profile field 1), sampling-frequency index 3 (48 kHz),
     * channel configuration 2, variable-bitrate buffer fullness (`0x7FF`), one raw block.
     */
    private fun writeAdtsHeader(out: ByteArray, auLength: Int) {
        val frameLength = ADTS_HEADER_LEN + auLength
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // syncword + MPEG-4 + layer 0 + no CRC
        out[2] = (((AAC_LC_PROFILE_FIELD shl 6) or (SAMPLE_RATE_INDEX shl 2) or (CHANNEL_CONFIG shr 2)) and 0xFF).toByte()
        out[3] = ((((CHANNEL_CONFIG and 0x3) shl 6) or ((frameLength shr 11) and 0x3)) and 0xFF).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte() // buffer fullness low bits + 0 raw blocks
    }

    companion object {
        /** Maps an OFFER audio codec name to an Android MIME type. */
        fun mimeFor(codecName: String): String = when (codecName.lowercase()) {
            "aac", "aac_ld", "aac_ld_adts" -> MediaFormat.MIMETYPE_AUDIO_AAC
            "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
            else -> throw IllegalArgumentException("unsupported audio codec '$codecName'")
        }

        /** True when the device has a `MediaCodec` **encoder** for [mime]. */
        fun hasEncoder(mime: String): Boolean {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }

        private const val ADTS_HEADER_LEN = 7
        /** ADTS profile field = MPEG-4 audio object type - 1; AAC-LC is object type 2. */
        private const val AAC_LC_PROFILE_FIELD = 1
        /** Sampling-frequency index for 48 kHz. */
        private const val SAMPLE_RATE_INDEX = 3
        /** Channel configuration for stereo. */
        private const val CHANNEL_CONFIG = 2
        private const val INPUT_TIMEOUT_US = 10_000L
    }
}
