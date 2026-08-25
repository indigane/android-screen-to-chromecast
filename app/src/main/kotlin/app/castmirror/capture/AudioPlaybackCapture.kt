package app.castmirror.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import app.castmirror.util.AppLog

/**
 * Captures other apps' playback audio off the same [MediaProjection] used for the screen.
 *
 * This is the unprivileged, GrapheneOS-friendly path: `AudioPlaybackCaptureConfiguration` reuses
 * the video projection (no second consent dialog) and needs only the runtime `RECORD_AUDIO`
 * permission — never `CAPTURE_AUDIO_OUTPUT` or `MODIFY_AUDIO_ROUTING`. It captures audio whose
 * usage is [AudioAttributes.USAGE_MEDIA], [AudioAttributes.USAGE_GAME] or
 * [AudioAttributes.USAGE_UNKNOWN]; calls, DRM-protected content and apps that set
 * `allowAudioPlaybackCapture=false` are silent by platform policy, not by anything here.
 *
 * The capture format is fixed at 48 kHz stereo 16-bit PCM, matching the Cast audio timebase and
 * what the AAC/Opus encoder wants. [read] blocks for PCM; the caller (the audio encoder) pumps it
 * into `MediaCodec`.
 *
 * @param projection the live projection from the video path.
 * @param sampleRate capture sample rate; Cast audio is 48 kHz.
 * @param channelCount 2 for stereo.
 */
class AudioPlaybackCapture(
    private val projection: MediaProjection,
    val sampleRate: Int = 48_000,
    val channelCount: Int = 2,
) {

    private val channelMask =
        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    private val minBufferBytes: Int = AudioRecord.getMinBufferSize(
        sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
    ).let { if (it <= 0) sampleRate * channelCount * 2 / 5 else it }

    /** A generous record buffer so a scheduling hiccup does not overrun and drop PCM. */
    val bufferSizeBytes: Int = minBufferBytes * BUFFER_FACTOR

    private var record: AudioRecord? = null

    @Volatile
    private var started = false

    /**
     * Builds and starts the [AudioRecord].
     *
     * Must be called after the [MediaProjection] is active. Throws if the record cannot be built
     * (for example when `RECORD_AUDIO` is not granted); the caller falls back to video-only.
     */
    fun start() {
        check(!started) { "AudioPlaybackCapture already started" }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val built = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built.release() }
            throw IllegalStateException("AudioRecord failed to initialise (RECORD_AUDIO denied?)")
        }
        built.startRecording()
        record = built
        started = true
        AppLog.i("audio-capture: started ${sampleRate}Hz ${channelCount}ch PCM16, buffer ${bufferSizeBytes}B")
    }

    /**
     * Reads up to [buffer].size bytes of PCM.
     *
     * @return the number of bytes read, `0` when none was available, or a negative
     *   `AudioRecord.ERROR_*` code.
     */
    fun read(buffer: ByteArray): Int {
        val r = record ?: return 0
        return r.read(buffer, 0, buffer.size)
    }

    /** Stops and releases the record. Idempotent. */
    fun release() {
        started = false
        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
    }

    private companion object {
        /** Multiplier over the minimum buffer size, for headroom against scheduling jitter. */
        const val BUFFER_FACTOR = 4
    }
}
