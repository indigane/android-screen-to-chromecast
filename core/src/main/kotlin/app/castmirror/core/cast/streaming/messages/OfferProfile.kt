package app.castmirror.core.cast.streaming.messages

import app.castmirror.core.CastAppIds
import app.castmirror.core.StreamingConstants

/** RTP extension tokens that can appear in an OFFER's `rtpExtensions` array. */
object RtpExtensions {
    /**
     * The token GMS offers on every stream when adaptive playout delay is enabled.
     *
     * It does **not** appear in openscreen's `message_constants.h`, which knows only
     * [INPUT_EVENTS]; it is a GMS/receiver-side extension corresponding to openscreen's
     * "Adaptive Latency" RTP header extension.
     */
    const val ADAPTIVE_PLAYOUT_DELAY = "adaptive_playout_delay"

    /** The only extension token openscreen defines. Not offered by either profile. */
    const val INPUT_EVENTS = "input_events"
}

/**
 * Which sender's OFFER dialect to speak.
 *
 * There are two mutually-incompatible-in-the-details conventions in the wild, and which one you
 * send has to match the receiver app you launched:
 *
 *  * [GMS_SYSTEM_MIRRORING] — the dialect Google's own Android "Cast screen" sender speaks, kept
 *    for compatibility with Google's Cast receiver. It differs materially from what openscreen's
 *    own `SenderSession` emits: extra `udpPort`/`storeTime`/`intraRefreshEnable`
 *    keys, omitted `codecParameter`/`receiverRtcpDscp`/`protection`, `profile: "main"` /
 *    `level: "4"`, `errorRecoveryMode: "castv2"`, `rtpExtensions: ["adaptive_playout_delay"]`,
 *    100 ms target delay, fixed tiny SSRCs, and the Android-TV payload types used
 *    unconditionally. This is the **default**, because it is the best-tested path on real
 *    Chromecast firmware — it is what every Android phone on earth sends.
 *
 *  * [OPENSCREEN_CHROME] — what Chrome and openscreen's `SenderSession` send, as described by the
 *    openscreen-derived protocol spec. Paired with app id `0F5096E8`.
 *
 * @property audioVideoAppId the receiver app id this dialect pairs with for a device that has
 *   video output.
 * @property audioOnlyAppId the receiver app id for a Cast-for-audio device.
 * @property targetDelayMs default end-to-end playout delay.
 * @property emitStoreTime emit the `storeTime` key (GMS only; always equal to `targetDelay`).
 * @property emitOfferUdpPort emit `offer.udpPort` (GMS only).
 * @property offerUdpPort the constant GMS writes there. It is vestigial — the real local port is
 *   chosen by the UDP socket layer — but always present.
 * @property emitIntraRefreshEnable emit `intraRefreshEnable` on video streams (GMS only).
 * @property emitCodecParameter emit `codecParameter` (openscreen emits `""`; GMS omits it).
 * @property emitProtection emit `protection` (openscreen emits `""`; GMS omits it).
 * @property resolutionsHeightFirst write `{"height":H,"width":W}` rather than width-first. GMS's
 *   Kotlin `Resolution` declares `height` first and Gson follows declaration order. Purely
 *   cosmetic to a receiver, which parses by name, but this reproduces the template byte for byte.
 * @property rtpExtensions the extension tokens offered on every stream. GMS sends
 *   `adaptive_playout_delay`, a token that does not exist in openscreen's `message_constants.h`.
 * @property errorRecoveryMode `"castv2"` for GMS, `""` (receiver-default) for openscreen.
 * @property videoProfile the `profile` string on video streams.
 * @property videoLevel the `level` string on video streams.
 * @property maxBitRate default video bitrate ceiling.
 * @property maxFrameRate default rational frame rate string.
 * @property resolutions default offered capture resolutions.
 * @property audioCodec the audio codec this dialect offers.
 * @property videoCodecs the video codecs this dialect offers, most-preferred first.
 * @property receiverRtcpEventLog whether to request RTCP event logs.
 * @property sharedKeys true when one `(aesKey, aesIvMask)` pair is reused across every stream
 *   (GMS) rather than a fresh pair per stream (openscreen).
 * @property fixedSsrcs true when SSRCs are the fixed small constants GMS uses (audio 1, video
 *   11, 12, ...) rather than randomly generated in the priority ranges.
 * @property initialSeqNum the first `seqNum`. GMS starts at 1; Chrome uses a large random value.
 * @property androidTvPayloadTypes true when the primary audio/video streams unconditionally use
 *   the "Android-TV hack" payload types (audio 127, video 96). GMS always does; for openscreen it
 *   is a runtime fallback (see `PayloadTypeFallbackPolicy`).
 * @property launchLanguage `language` to send in LAUNCH, or `null` to omit. GMS omits it.
 * @property launchSupportedAppTypes `supportedAppTypes` for LAUNCH, or `null` to omit. GMS omits.
 * @property cliName short name used by command-line flags and log lines.
 */
enum class OfferProfile(
    val audioVideoAppId: String,
    val audioOnlyAppId: String,
    val targetDelayMs: Int,
    val emitStoreTime: Boolean,
    val emitOfferUdpPort: Boolean,
    val offerUdpPort: Int,
    val emitIntraRefreshEnable: Boolean,
    val emitCodecParameter: Boolean,
    val emitProtection: Boolean,
    val resolutionsHeightFirst: Boolean,
    val rtpExtensions: List<String>,
    val errorRecoveryMode: String,
    val videoProfile: String,
    val videoLevel: String,
    val maxBitRate: Int,
    val maxFrameRate: String,
    val resolutions: List<Resolution>,
    val audioCodec: String,
    val videoCodecs: List<String>,
    val receiverRtcpEventLog: Boolean,
    val sharedKeys: Boolean,
    val fixedSsrcs: Boolean,
    val initialSeqNum: Int,
    val androidTvPayloadTypes: Boolean,
    val launchLanguage: String?,
    val launchSupportedAppTypes: List<String>?,
    val cliName: String,
) {
    /**
     * Google's Android "Cast screen" dialect, kept for compatibility with Google's Cast receiver.
     *
     * `GmsProfileTest` compares the generated JSON against a fixed reference OFFER key by key.
     *
     * Note that only **`h264`** is listed in [videoCodecs] even though GMS also offers `vp9` as a
     * second video stream. That is a deliberate, documented divergence: this core starts one
     * frame source per negotiated stream, so offering two video codecs implies being able to run
     * two encoders. Pass `videoCodecs = listOf("h264", "vp9")` to reproduce GMS exactly.
     */
    GMS_SYSTEM_MIRRORING(
        audioVideoAppId = CastAppIds.ANDROID_MIRRORING_AUDIO_VIDEO,
        audioOnlyAppId = CastAppIds.ANDROID_MIRRORING_AUDIO_ONLY,
        targetDelayMs = 100,
        emitStoreTime = true,
        emitOfferUdpPort = true,
        offerUdpPort = 2346,
        emitIntraRefreshEnable = true,
        emitCodecParameter = false,
        emitProtection = false,
        resolutionsHeightFirst = true,
        rtpExtensions = listOf(RtpExtensions.ADAPTIVE_PLAYOUT_DELAY),
        errorRecoveryMode = "castv2",
        videoProfile = "main",
        videoLevel = "4",
        maxBitRate = 6_000_000,
        maxFrameRate = "60000/1001",
        resolutions = listOf(Resolution(1280, 720)),
        audioCodec = "aac",
        videoCodecs = listOf("h264"),
        receiverRtcpEventLog = true,
        sharedKeys = true,
        fixedSsrcs = true,
        initialSeqNum = 1,
        androidTvPayloadTypes = true,
        launchLanguage = null,
        launchSupportedAppTypes = null,
        cliName = "gms",
    ),

    /** Chrome / openscreen `SenderSession` dialect, per the protocol spec. */
    OPENSCREEN_CHROME(
        audioVideoAppId = CastAppIds.CAST_STREAMING_AUDIO_VIDEO,
        audioOnlyAppId = CastAppIds.CAST_STREAMING_AUDIO_ONLY,
        targetDelayMs = StreamingConstants.DEFAULT_TARGET_DELAY_MS,
        emitStoreTime = false,
        emitOfferUdpPort = false,
        offerUdpPort = 0,
        emitIntraRefreshEnable = false,
        emitCodecParameter = true,
        emitProtection = true,
        resolutionsHeightFirst = false,
        rtpExtensions = emptyList(),
        errorRecoveryMode = "",
        videoProfile = "",
        videoLevel = "",
        maxBitRate = StreamingConstants.DEFAULT_VIDEO_MAX_BITRATE,
        maxFrameRate = "30",
        resolutions = listOf(Resolution(1920, 1080)),
        audioCodec = "opus",
        videoCodecs = listOf("h264", "vp8"),
        receiverRtcpEventLog = false,
        sharedKeys = false,
        fixedSsrcs = false,
        initialSeqNum = -1,   // -1 means "pick a large random start", as Chrome does
        androidTvPayloadTypes = false,
        launchLanguage = "en-US",
        launchSupportedAppTypes = listOf("WEB", "ANDROID_TV"),
        cliName = "openscreen",
    );

    /** The app id to launch for a device with the given capabilities. */
    fun appIdFor(hasVideoOutput: Boolean): String =
        if (hasVideoOutput) audioVideoAppId else audioOnlyAppId

    /** Default audio bitrate for this dialect's audio codec. */
    val audioBitRate: Int get() = StreamingConstants.DEFAULT_AUDIO_BITRATE

    companion object {
        /** The profile this implementation uses unless told otherwise. */
        val DEFAULT = GMS_SYSTEM_MIRRORING

        /** Looks a profile up by [cliName], case-insensitively. `chrome` aliases `openscreen`. */
        fun byName(name: String): OfferProfile? = when (name.lowercase()) {
            "chrome" -> OPENSCREEN_CHROME
            else -> entries.firstOrNull { it.cliName == name.lowercase() }
        }
    }
}
