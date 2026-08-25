package app.castmirror.settings

import android.content.Context
import android.content.SharedPreferences
import app.castmirror.core.CastAppIds
import app.castmirror.core.LaunchRung
import app.castmirror.core.SessionConfig
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.messages.Resolution

/**
 * Which receiver application ids to try, and in which OFFER dialect.
 *
 * The app id and the dialect are not independent: `674A0243` (what Google's own "Cast screen"
 * launches) expects the GMS OFFER shape, and `0F5096E8` (Chrome mirroring) expects openscreen's.
 * Pairing them wrongly produces an ANSWER-less silence that is very hard to diagnose, so the
 * choice is exposed as one setting rather than two.
 */
enum class AppIdMode(
    /** Label shown in the settings spinner. */
    val label: String,
) {
    /** GMS's id first, Chrome's as a fallback rung. Matches the core's default. */
    LADDER("Default ladder (674A0243 → 0F5096E8)"),

    /** Only what every Android phone sends. Best-tested path on real Chromecast firmware. */
    GMS_ONLY("674A0243 only (GMS dialect)"),

    /** Only Chrome's mirroring receiver. The path that supports VP8. */
    OPENSCREEN_ONLY("0F5096E8 only (openscreen dialect)");

    /**
     * Builds the launch ladder for a device.
     *
     * @param hasVideoOutput the device's mDNS `ca & 0x01` bit; audio-only receivers take the
     *   audio-only app ids, exactly as GMS decides it.
     */
    fun ladder(hasVideoOutput: Boolean): List<LaunchRung> = when (this) {
        LADDER -> SessionConfig.ladderFor(hasVideoOutput)
        GMS_ONLY -> listOf(
            LaunchRung(
                if (hasVideoOutput) CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO
                else CastAppIds.ANDROID_MIRRORING_AUDIO_ONLY,
                OfferProfile.GMS_SYSTEM_MIRRORING,
            )
        )
        OPENSCREEN_ONLY -> listOf(
            LaunchRung(
                if (hasVideoOutput) CastAppIds.CAST_STREAMING_AUDIO_VIDEO
                else CastAppIds.CAST_STREAMING_AUDIO_ONLY,
                OfferProfile.OPENSCREEN_CHROME,
            )
        )
    }
}

/** A capture resolution preset, expressed as its long edge. */
enum class ResolutionPreset(
    /** Label shown in the settings spinner. */
    val label: String,
    /** The long edge in pixels. */
    val longEdge: Int,
    /** The short edge in pixels. */
    val shortEdge: Int,
) {
    /** 1280x720 — the resolution GMS offers, and the safest choice on old receivers. */
    P720("720p (1280x720)", 1280, 720),

    /** 1920x1080 — what Chrome offers. Needs a receiver that reports a 1080p panel. */
    P1080("1080p (1920x1080)", 1920, 1080);

    /**
     * The fixed landscape encode resolution (`width >= height`).
     *
     * Cast cannot renegotiate resolution mid-session, so the encode frame is landscape for the
     * whole session regardless of the phone's orientation. Rotation is handled by
     * [app.castmirror.capture.LetterboxCompositor], which fits the current screen into this frame
     * with black bars rather than by changing the frame.
     */
    fun landscape(): Resolution = Resolution(longEdge, shortEdge)
}

/** The video codec to offer and encode with. */
enum class CodecChoice(
    /** Label shown in the settings spinner. */
    val label: String,
    /** The codec name as it appears in the OFFER, and as [app.castmirror.encode.VideoEncoderFrameSource] expects it. */
    val offerName: String,
) {
    /** H.264, the only codec the GMS receiver app accepts. */
    H264("H.264 (video/avc)", "h264"),

    /** VP8. Only the openscreen receiver (`0F5096E8`) offers it. */
    VP8("VP8 (video/x-vnd.on2.vp8)", "vp8");
}

/**
 * The user's tunables, persisted in [SharedPreferences].
 *
 * Every value is read fresh from preferences on access so that a change made in the UI is picked
 * up by the next session without any explicit propagation.
 */
class MirrorSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("castmirror", Context.MODE_PRIVATE)

    /** Capture resolution preset. */
    var resolution: ResolutionPreset
        get() = enumOr(KEY_RESOLUTION, ResolutionPreset.entries, ResolutionPreset.P720)
        set(v) = prefs.edit().putString(KEY_RESOLUTION, v.name).apply()

    /** Video bitrate ceiling in kilobits per second. */
    var bitrateKbps: Int
        get() = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE_KBPS).coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
        set(v) = prefs.edit().putInt(KEY_BITRATE, v.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)).apply()

    /** Target frame rate in frames per second. */
    var frameRate: Int
        get() = prefs.getInt(KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
        set(v) = prefs.edit().putInt(KEY_FRAME_RATE, v).apply()

    /** Video codec. */
    var codec: CodecChoice
        get() = enumOr(KEY_CODEC, CodecChoice.entries, CodecChoice.H264)
        set(v) = prefs.edit().putString(KEY_CODEC, v.name).apply()

    /** Receiver app id / OFFER dialect pairing. */
    var appIdMode: AppIdMode
        get() = enumOr(KEY_APP_ID_MODE, AppIdMode.entries, AppIdMode.LADDER)
        set(v) = prefs.edit().putString(KEY_APP_ID_MODE, v.name).apply()

    /** The last manually entered receiver address, offered as a default in the "Enter IP" dialog. */
    var lastManualHost: String
        get() = prefs.getString(KEY_MANUAL_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MANUAL_HOST, v).apply()

    /**
     * Whether to log the full per-second STATS line and per-event RTCP / key-frame / retransmit
     * detail.
     *
     * Default **off**: a normal session keeps a minimal, always-on log (session start, state
     * transitions, the ANSWER summary, a terse five-second fps/bitrate/loss line, teardown reason,
     * and a loud line whenever a frame is shed). Turning this on adds the dense per-second STATS
     * line and per-event RTCP detail, for diagnosing a quality problem.
     */
    var verboseDiagnostics: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE_DIAGNOSTICS, false)
        set(v) = prefs.edit().putBoolean(KEY_VERBOSE_DIAGNOSTICS, v).apply()

    /**
     * Whether to also capture and stream internal (other apps') audio.
     *
     * Default **on**. Uses `AudioPlaybackCaptureConfiguration` off the same `MediaProjection` as
     * the video, so it needs no privileged permission — only the runtime `RECORD_AUDIO` grant. It
     * captures media/game/unknown-usage audio; calls, DRM-protected apps and apps that opt out of
     * playback capture are silent by policy. If `RECORD_AUDIO` is not granted the session mirrors
     * video only.
     */
    var mirrorAudio: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_AUDIO, true)
        set(v) = prefs.edit().putBoolean(KEY_MIRROR_AUDIO, v).apply()

    /**
     * Whether the AAC encoder output is wrapped in ADTS framing before being sent.
     *
     * Default **on**, which is what Google's Cast receiver expects for the `aac` codec. This is the
     * one-flip fallback for the "audio plays silent" case: turning it **off** sends raw AAC access
     * units instead. There is no visible control for it — long-press the audio caption on the main
     * screen to toggle it for a device test.
     */
    var aacAdts: Boolean
        get() = prefs.getBoolean(KEY_AAC_ADTS, true)
        set(v) = prefs.edit().putBoolean(KEY_AAC_ADTS, v).apply()

    /**
     * Seconds between forced key frames.
     *
     * A deliberate divergence from GMS's 60 s: see [KEY_FRAME_INTERVAL_SECONDS].
     */
    val keyFrameIntervalSeconds: Int get() = KEY_FRAME_INTERVAL_SECONDS

    /** Video bitrate ceiling in bits per second, as the OFFER and the encoder want it. */
    val bitrateBps: Int get() = bitrateKbps * 1000

    private fun <E : Enum<E>> enumOr(key: String, values: List<E>, fallback: E): E {
        val name = prefs.getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == name } ?: fallback
    }

    companion object {
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_BITRATE = "bitrate_kbps"
        private const val KEY_FRAME_RATE = "frame_rate"
        private const val KEY_CODEC = "codec"
        private const val KEY_APP_ID_MODE = "app_id_mode"
        private const val KEY_MANUAL_HOST = "manual_host"
        private const val KEY_VERBOSE_DIAGNOSTICS = "verbose_diagnostics"
        private const val KEY_MIRROR_AUDIO = "mirror_audio"
        private const val KEY_AAC_ADTS = "aac_adts"

        /** Audio bitrate in bits per second for the AAC / Opus encoder. */
        const val AUDIO_BITRATE_BPS = 128_000

        /** GMS's ceiling for system mirroring. */
        const val DEFAULT_BITRATE_KBPS = 6_000

        /** Below this the picture is unusable at 720p. */
        const val MIN_BITRATE_KBPS = 500

        /** Above this a Chromecast's decoder and a 2.4 GHz link both start to struggle. */
        const val MAX_BITRATE_KBPS = 20_000

        /** Frame rates worth offering. GMS offers 60000/1001 and encodes at 59. */
        val FRAME_RATE_CHOICES = listOf(24, 30, 60)

        private const val DEFAULT_FRAME_RATE = 30

        /**
         * Seconds between forced key frames: **1**, a deliberate divergence from GMS's 60.
         *
         * GMS can afford a 60-second GOP because it never drops an encoded frame and leans on
         * receiver-driven PLI to re-sync. This sender is not there yet, and a single missing frame
         * on H.264 datamoshes every following P-frame until the next key frame. A ~1-second GOP
         * (an I-frame roughly every 30 frames at 30 fps) bounds any residual corruption to ~1 s so
         * the picture always self-heals quickly. Key frames are larger, but at 6 Mbps 720p an
         * I-frame per second is comfortably affordable. See REVIEW.md, finding #13.
         */
        private const val KEY_FRAME_INTERVAL_SECONDS = 1
    }
}
