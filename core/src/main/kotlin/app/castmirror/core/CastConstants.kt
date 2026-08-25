package app.castmirror.core

/**
 * Cast Streaming receiver application ids.
 *
 * Verbatim from `openscreen:cast/common/public/cast_streaming_app_ids.h:26-56`.
 */
object CastAppIds {
    /** Chrome tab/desktop mirroring, audio + video. Our fallback rung. */
    const val CAST_STREAMING_AUDIO_VIDEO = "0F5096E8"

    /** Chrome mirroring, audio only (Cast-for-audio devices). */
    const val CAST_STREAMING_AUDIO_ONLY = "85CDB22F"

    /**
     * Android screen mirroring, audio + video. **The default.**
     *
     * This is the id Google's own Android "Cast screen" launches for any device with video output,
     * so it is the best-supported path on real receiver firmware; `0F5096E8` (Chrome mirroring) is
     * kept only as a fallback rung.
     *
     * It expects the [OfferProfile.GMS_SYSTEM_MIRRORING] dialect, including the Android-TV RTP
     * payload-type quirk on the primary streams.
     */
    const val ANDROID_MIRRORING_AUDIO_VIDEO = "674A0243"

    /**
     * Android screen mirroring, audio only.
     *
     * What GMS launches for a device with audio output but no video output
     * (`audio_mirroring_app_id`).
     */
    const val ANDROID_MIRRORING_AUDIO_ONLY = "8E6C866D"

    /** Android *app* streaming (Cast Remote Display), audio + video. Not screen mirroring. */
    const val ANDROID_APP_STREAMING_AUDIO_VIDEO = "96084372"

    /** iOS app streaming, audio + video. Not relevant to an Android sender. */
    const val IOS_APP_STREAMING_AUDIO_VIDEO = "BFD92C23"

    /**
     * App ids for a receiver with video output (`ca & 0x01`), most-preferred first.
     *
     * GMS's id comes first because it is what every Android phone sends and therefore the
     * best-tested path on real firmware. See `SessionConfig.DEFAULT_LAUNCH_LADDER`, which pairs
     * each id with the OFFER dialect it expects.
     */
    val AUDIO_VIDEO_LADDER = listOf(ANDROID_MIRRORING_AUDIO_VIDEO, CAST_STREAMING_AUDIO_VIDEO)

    /** App ids for an audio-only receiver (`ca & 0x04` without `ca & 0x01`). */
    val AUDIO_ONLY_LADDER = listOf(ANDROID_MIRRORING_AUDIO_ONLY, CAST_STREAMING_AUDIO_ONLY)

    /** Every id worth probing with `GET_APP_AVAILABILITY` at startup. */
    val ALL_MIRRORING = AUDIO_VIDEO_LADDER + AUDIO_ONLY_LADDER
}

/**
 * Protocol-wide constants from `openscreen:cast/streaming/public/constants.h` and
 * `chromium:components/mirroring/service/mirror_settings.cc`.
 */
object StreamingConstants {
    /** Video RTP timebase, in ticks per second. */
    const val VIDEO_TIMEBASE = 90_000

    /** Audio RTP timebase, in ticks per second (equal to the Opus sample rate). */
    const val AUDIO_TIMEBASE = 48_000

    /**
     * openscreen's default end-to-end playout delay, in milliseconds.
     *
     * GMS's system mirroring preset uses **100** instead (with `maxTargetDelay` 500 negotiated
     * through the adaptive-playout-delay RTP extension); see `OfferProfile.targetDelayMs`.
     */
    const val DEFAULT_TARGET_DELAY_MS = 400

    /** Minimum accepted `targetDelay`; out-of-range values are silently replaced with the default. */
    const val MIN_TARGET_DELAY_MS = 0

    /** Maximum accepted `targetDelay`. */
    const val MAX_TARGET_DELAY_MS = 5_000

    /**
     * Maximum number of frames that may be in flight unacknowledged.
     *
     * "This value is carefully chosen such that it fits in the 8-bits range for frame IDs. It is
     * also less than half of the full 8-bits range such that logic can handle wrap around and
     * compare two frame IDs meaningfully." (`constants.h:46`)
     */
    const val MAX_UNACKED_FRAMES = 120

    /** RTCP sender-report cadence. */
    const val RTCP_REPORT_INTERVAL_MS = 500L

    /** Media-level inactivity timeout from [SSP]: tear down after this long with no RTCP. */
    const val MEDIA_TIMEOUT_MS = 15_000L

    /** Pacer burst interval (`sender_packet_router.h`). */
    const val BURST_INTERVAL_MS = 10L

    /**
     * Floor on the kickstart resend interval, in milliseconds.
     *
     * Kickstart resends the last packet of the newest frame so the receiver learns a frame exists
     * even when *all* of its packets were lost. openscreen's interval is `targetDelay / 20`, which
     * at GMS's 100 ms target is a hair-trigger 5 ms; on a static screen (a new "repeat" frame only
     * every ~70 ms) that fires several redundant kickstarts per second even with zero loss. This
     * floor keeps a kickstart from firing until the newest frame has genuinely gone unacknowledged
     * for longer than one repeat-frame gap, so with loss ≈ 0 kickstarts stay rare.
     */
    const val MIN_KICKSTART_INTERVAL_MS = 100L

    /** Pacer ceiling, `24 << 20` bits/s = 25165824 (not 24 000 000 — the shift is deliberate). */
    const val MAX_BURST_BITRATE = 24 shl 20

    /** Max RTP packet size for IPv4 UDP on Ethernet: `1500 - 20 - 8`. */
    const val MAX_RTP_PACKET_SIZE_IPV4 = 1472

    /** Max RTP packet size for IPv6 UDP on Ethernet: `1500 - 40 - 8`. */
    const val MAX_RTP_PACKET_SIZE_IPV6 = 1452

    /** Audio SSRCs live in the "higher priority" range and are paced ahead of video. */
    val AUDIO_SSRC_RANGE = 1..50_000

    /** Video SSRCs live in the "normal priority" range. */
    val VIDEO_SSRC_RANGE = 50_001..100_000

    /** libcast's DSCP suggestion for a session containing video: AF41. */
    const val DSCP_AF41 = 0x22

    /** libcast's DSCP suggestion for an audio-only session: EF. */
    const val DSCP_EF = 0x2E

    /** Chrome's default video max bitrate. */
    const val DEFAULT_VIDEO_MAX_BITRATE = 5_000_000

    /** Chrome's Opus bitrate. */
    const val DEFAULT_AUDIO_BITRATE = 128_000

    /** libcast's audio bitrate floor. */
    const val MIN_AUDIO_BITRATE = 32_000

    /** libcast's hard minimum offered resolution. */
    const val MIN_RESOLUTION_WIDTH = 320

    /** libcast's hard minimum offered resolution. */
    const val MIN_RESOLUTION_HEIGHT = 240

    /** Reply timeout for OFFER / GET_CAPABILITIES (`session_messenger.cc:25`). */
    const val REPLY_TIMEOUT_MS = 4_000L
}
