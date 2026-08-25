package app.castmirror.core.cast.streaming

import app.castmirror.core.StreamingConstants

/**
 * The two distinguished frame-id values, from `openscreen:cast/streaming/public/frame_id.h`.
 *
 * Getting these wrong is invisible on the sender and fatal on the receiver, so they are named
 * rather than spelled out at each use site.
 */
object FrameIds {
    /**
     * `FrameId::first()` — the id of the **first frame of a stream**. Zero, not one.
     *
     * ```
     * // The identifier for the first frame in a stream.
     * static constexpr FrameId first() { return FrameId(0); }
     * ```
     */
    const val FIRST = 0

    /**
     * `FrameId::leader()` — "the frame before the first"; never transmitted.
     *
     * ```
     * // A virtual identifier, representing the frame before the first. There should
     * // never actually be a frame streamed with this identifier. Instead, this is
     * // used in various components to represent a "not yet seen/processed the first
     * // frame" state.
     * static constexpr FrameId leader() { return FrameId(-1); }
     * ```
     *
     * Every one of the sender's frame-id state variables (`checkpoint_frame_id_`,
     * `last_enqueued_frame_id_`, `latest_expected_frame_id_`, `picture_lost_at_frame_id_`,
     * `last_enqueued_key_frame_id_`) is initialised to this in `sender_impl.h:202-241`.
     */
    const val LEADER = -1
}

/**
 * One encoded access unit handed to the [Sender].
 *
 * @property frameId the full 32-bit logical frame id, starting at [FrameIds.FIRST] (**zero**) and
 *   incrementing by one per frame *per stream*. Only the low 8 bits go on the wire. Audio and
 *   video have independent frame-id spaces.
 *
 *   Zero is **not** reserved: libcast's "null" `FrameId` is `INT64_MIN`
 *   (`frame_id.h:42`), and `FrameId::first()` is explicitly `FrameId(0)` (`frame_id.h:104`).
 *   openscreen's receiver starts with `last_frame_consumed_ = FrameId::leader() = -1` and then
 *   waits for `last_frame_consumed_ + 1`, i.e. frame **0**; with the default
 *   `allow_skip_to_keyframe = false` (`session_config.h:72`) it never skips ahead, so a sender
 *   that starts at 1 stalls the receiver forever. GMS starts at 0 too.
 * @property isKeyFrame true for an IDR / VP8 key frame.
 * @property referencedFrameId the frame this one depends on. For a key frame it **must equal**
 *   [frameId]; for a delta frame in a simple linear chain it is `frameId - 1`.
 * @property rtpTimestamp presentation time in the stream's RTP timebase (90 kHz video,
 *   48 kHz audio). Must be **strictly** monotonically increasing across frames — libcast
 *   `CHECK`s this, and MediaCodec can emit equal presentation times on a static screen, so
 *   coalesce or drop such frames rather than sending a non-increasing timestamp.
 * @property data the complete encoded frame, **unencrypted**. For H.264 this is Annex-B with
 *   SPS/PPS prepended in-band to every key frame; Cast has no out-of-band codec-config channel.
 *   Audio payload bytes are described under "Audio frames" below.
 * @property captureTimeMs monotonic capture time in milliseconds, used for in-flight accounting
 *   and the sender report's NTP/RTP anchor.
 * @property newPlayoutDelayMs when greater than zero, packet 0 of this frame carries the
 *   adaptive-latency extension announcing a new playout delay. Leave at 0 unless you are
 *   changing the delay mid-session.
 *
 * ### Audio frames (the contract an `AudioEncoderFrameSource` must satisfy)
 *
 * Cast carries audio on its own stream (its own SSRC, frame-id and sequence-number spaces), and
 * the same [Sender]/[app.castmirror.core.cast.streaming.rtp.RtpPacketizer]/`FrameCrypto` path
 * transports it — the RTP/RTCP layer is codec-agnostic and just moves opaque frame bytes. An audio
 * frame source must produce each access unit as an EncodedFrame with:
 *
 *  * **`isKeyFrame = true` and `referencedFrameId = frameId`** — every audio access unit is
 *    independently decodable (AAC-LC has no inter-frame prediction; each Opus packet stands alone),
 *    so each frame references only itself. This sets the RTP key-frame bit and self-reference the
 *    receiver expects for audio.
 *  * **`rtpTimestamp` in sample-rate ticks** at the stream's 48 kHz timebase, **strictly
 *    increasing**. One 1024-sample AAC-LC frame advances it by 1024; a 10 ms Opus frame by 480, a
 *    20 ms frame by 960. Derive it from the sample count (or `presentationTimeUs * 48000 / 1e6`),
 *    never from a wall clock.
 *  * **`data` = one encoded audio access unit**, and nothing else:
 *      * **AAC (GMS profile, `codecName = "aac"`, payload type 127):** one **ADTS-framed AAC-LC**
 *        access unit — a 7-byte ADTS header (syncword `0xFFF`, MPEG-4, AAC-LC, sample-rate index 3
 *        = 48 kHz, channel config 2 = stereo) followed by the raw AU. Cast has no audio analogue of
 *        video's in-band SPS/PPS and sends no AudioSpecificConfig, so ADTS — which is
 *        self-describing — is how the receiver learns the format. Android's `audio/mp4a-latm`
 *        encoder emits **raw** AUs plus a `csd-0` (`BUFFER_FLAG_CODEC_CONFIG`) buffer: consume and
 *        discard the `csd-0` (never send it as a frame) and **prepend the ADTS header** to each AU.
 *        If a device's encoder already emits ADTS (syncword present), pass it through unchanged.
 *        SPEC-UNVERIFIED against a live receiver: GMS's internal codec name for aac is
 *        `aac_ld_adts` and it toggles an ADTS flag, which is the evidence for ADTS; the fallback if
 *        a device test shows silence is raw AUs (no ADTS) with the receiver inferring the config
 *        from the OFFER's `codecName`/`sampleRate`/`channels`.
 *      * **Opus (openscreen profile, `codecName = "opus"`, payload type 96):** one **raw Opus
 *        packet**, no ADTS, no TOC wrapper, no AudioSpecificConfig — 48 kHz, stereo, 10 ms or 20 ms
 *        frames. This is fully specified (spec §8.4 / openscreen).
 *
 * Unlike video, audio frames **may** be dropped safely (each is independent), so an audio source
 * under backpressure may shed a stale access unit rather than stall; the core pump treats audio and
 * video uniformly (it back-pressures rather than drops) and either policy is correct for audio.
 */
data class EncodedFrame(
    val frameId: Int,
    val isKeyFrame: Boolean,
    val referencedFrameId: Int,
    val rtpTimestamp: Long,
    val data: ByteArray,
    val captureTimeMs: Long = System.nanoTime() / 1_000_000,
    val newPlayoutDelayMs: Int = 0,
) {
    init {
        require(frameId >= FrameIds.FIRST) { "frame id must be >= ${FrameIds.FIRST}, was $frameId" }
        if (isKeyFrame) {
            require(referencedFrameId == frameId) {
                "a key frame must reference itself (frameId=$frameId ref=$referencedFrameId)"
            }
        }
        require(newPlayoutDelayMs in 0..65535) { "newPlayoutDelay must fit in a uint16" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        return frameId == other.frameId && isKeyFrame == other.isKeyFrame &&
            referencedFrameId == other.referencedFrameId && rtpTimestamp == other.rtpTimestamp &&
            data.contentEquals(other.data) && captureTimeMs == other.captureTimeMs &&
            newPlayoutDelayMs == other.newPlayoutDelayMs
    }

    override fun hashCode(): Int {
        var h = frameId
        h = 31 * h + isKeyFrame.hashCode()
        h = 31 * h + referencedFrameId
        h = 31 * h + rtpTimestamp.hashCode()
        h = 31 * h + data.contentHashCode()
        return h
    }
}

/**
 * A frame that has been encrypted and is ready to packetize.
 *
 * @property frame the metadata of the source [EncodedFrame].
 * @property ciphertext the AES-128-CTR output; the same length as the plaintext.
 */
class EncryptedFrame(val frame: EncodedFrame, val ciphertext: ByteArray)

/**
 * The source of encoded frames the [SessionController] pumps.
 *
 * On Android this is implemented over `MediaCodec`; in tests it is a synthetic generator. Keeping
 * it an interface is what keeps the protocol core free of Android APIs.
 */
interface FrameSource {
    /**
     * Blocks for up to [timeoutMs] and returns the next frame, or `null` if none is available.
     *
     * The implementation owns reference-id and RTP-timestamp assignment, because it is the only
     * component that knows the encoder's presentation-time clock. It does **not** own the frame
     * id: the sender does, and passes the id it expects as [nextFrameId]. The returned frame must
     * carry `frameId == nextFrameId` or it will be skipped.
     *
     * The reason is congestion control. When the sender sheds a frame (the in-flight budget or the
     * 120-frame id span is exhausted) the id is *not* consumed, so a source that kept its own
     * counter would immediately desynchronise. This mirrors libcast, where the encoder driver
     * calls `GetNextFrameId()` before producing each frame.
     *
     * Note that a shed frame breaks the reference chain: the next delta frame will reference a
     * frame the receiver never saw. That is expected — the receiver responds with a PLI and
     * [requestKeyFrame] follows.
     *
     * @param timeoutMs how long to block waiting for the encoder.
     * @param nextFrameId the frame id the sender requires on the returned frame.
     */
    fun nextFrame(timeoutMs: Long, nextFrameId: Int): EncodedFrame?

    /**
     * Called when the receiver asks for a key frame (a PLI arrived, or the checkpoint stalled).
     *
     * On Android this maps to
     * `MediaCodec.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)`.
     */
    fun requestKeyFrame() {}

    /** Called when the sender wants the encoder to target a different bitrate. */
    fun requestBitrate(bitsPerSecond: Int) {}

    /** Releases any resources held by the source. */
    fun close() {}
}

/** Utility conversions between wall/monotonic time and RTP timestamps. */
object RtpTime {
    /**
     * Converts a duration in microseconds into RTP ticks at [timebase].
     *
     * @param micros duration in microseconds.
     * @param timebase ticks per second — 90000 for video, 48000 for audio.
     */
    fun ticksFromMicros(micros: Long, timebase: Int): Long = micros * timebase / 1_000_000L

    /** Converts a duration in milliseconds into RTP ticks at [timebase]. */
    fun ticksFromMillis(millis: Long, timebase: Int): Long = millis * timebase / 1_000L

    /** Converts RTP ticks at [timebase] into milliseconds. */
    fun millisFromTicks(ticks: Long, timebase: Int): Long = ticks * 1_000L / timebase

    /** RTP ticks per 10 ms Opus frame at the standard 48 kHz audio timebase: 480. */
    const val OPUS_TICKS_PER_10MS = StreamingConstants.AUDIO_TIMEBASE / 100
}
