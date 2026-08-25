package app.castmirror.core.cast.streaming.messages

import app.castmirror.core.StreamingConstants
import app.castmirror.core.cast.streaming.rtp.RtpPayloadTypes
import app.castmirror.core.util.Bytes
import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj
import java.security.SecureRandom

/** `Stream.type` discriminator. */
enum class StreamType(
    /** The value written to the `type` key. */
    val wire: String,
) {
    /** An audio stream. */
    AUDIO_SOURCE("audio_source"),

    /** A video stream. */
    VIDEO_SOURCE("video_source");

    companion object {
        /** Parses a wire value, or `null` for anything unrecognised (such a stream is skipped). */
        fun fromWire(s: String?): StreamType? = entries.firstOrNull { it.wire == s }
    }
}

/** A sender-supported capture resolution. */
data class Resolution(
    /** Width in pixels. */
    val width: Int,
    /** Height in pixels. */
    val height: Int,
) {
    /**
     * Serializes to `{"width":W,"height":H}`, or `{"height":H,"width":W}` when [heightFirst].
     *
     * Key order is cosmetic — receivers parse by name — but GMS's Kotlin `Resolution` declares
     * `height` first and Gson follows declaration order, so height-first reproduces the observed
     * template exactly.
     */
    fun toJson(heightFirst: Boolean = false): Json.Obj = jsonObj {
        if (heightFirst) { put("height", height); put("width", width) }
        else { put("width", width); put("height", height) }
    }

    /** True if this meets libcast's 320x240 floor for a sender-offered resolution. */
    fun isValid(): Boolean =
        width >= StreamingConstants.MIN_RESOLUTION_WIDTH &&
            height >= StreamingConstants.MIN_RESOLUTION_HEIGHT

    companion object {
        /** Parses `{"width":..,"height":..}`; returns `null` when either key is missing. */
        fun parse(o: Json.Obj): Resolution? {
            val w = o["width"]?.asIntOrNull() ?: return null
            val h = o["height"]?.asIntOrNull() ?: return null
            return Resolution(w, h)
        }
    }
}

/**
 * The fields every OFFER stream carries, audio or video.
 *
 * @property index identifier; must start at 0 and increase by exactly 1 across the offer.
 *   The ANSWER's `sendIndexes` refers to these.
 * @property type audio or video.
 * @property codecName `opus`/`aac` or `h264`/`vp8`/`vp9`/`hevc`/`av1`. An unknown codec causes
 *   the receiver to drop *that stream only*, which is why offering several codecs is free.
 * @property codecParameter RFC 6381 codec string. Prefix-validated against [codecName]; a
 *   mismatch is a hard error for the *whole* offer, so the default is the always-safe `""`.
 *   Omitted entirely by the GMS profile.
 * @property rtpPayloadType see [RtpPayloadTypes].
 * @property ssrc this stream's sender SSRC; must be unique across the offer.
 * @property targetDelayMs end-to-end playout delay. Out-of-range values are silently replaced
 *   with 400 by the receiver rather than rejected.
 * @property storeTimeMs GMS emits this alongside `targetDelay` and always equal to it. It does
 *   not exist in openscreen at all. `null` omits the key.
 * @property channels 2 for audio, 1 for video by convention. GMS omits it on video streams.
 * @property aesKey exactly 16 bytes, emitted as 32 lower-case hex digits.
 * @property aesIvMask exactly 16 bytes, emitted as 32 lower-case hex digits.
 * @property rtpTimebase denominator of `timeBase`; 90000 for video, the sample rate for audio.
 * @property errorRecoveryMode `""` leaves the receiver on its `castv2` default; GMS states
 *   `"castv2"` explicitly. `castv2` means the receiver may not drop a video packet and there is
 *   no intra-refresh after the first key frame.
 * @property receiverRtcpEventLog ask the receiver to send RTCP event logs.
 * @property receiverRtcpDscp DSCP the receiver should mark its RTCP with. Must be in `[0,63]`
 *   and **identical across every stream in the offer** or the offer is rejected. GMS omits it
 *   entirely, which sidesteps that hazard.
 * @property rtpExtensions sender-supported RTP extensions. Emitted only when non-empty.
 */
data class StreamCommon(
    val index: Int,
    val type: StreamType,
    val codecName: String,
    val rtpPayloadType: Int,
    val ssrc: Long,
    val aesKey: ByteArray,
    val aesIvMask: ByteArray,
    val rtpTimebase: Int,
    val codecParameter: String = "",
    val targetDelayMs: Int = StreamingConstants.DEFAULT_TARGET_DELAY_MS,
    val storeTimeMs: Int? = null,
    val channels: Int = if (type == StreamType.AUDIO_SOURCE) 2 else 1,
    val errorRecoveryMode: String = "",
    val receiverRtcpEventLog: Boolean = false,
    val receiverRtcpDscp: Int? = null,
    val rtpExtensions: List<String> = emptyList(),
) {
    init {
        require(index >= 0) { "stream index must be >= 0" }
        require(aesKey.size == 16) { "aesKey must be 16 bytes, was ${aesKey.size}" }
        require(aesIvMask.size == 16) { "aesIvMask must be 16 bytes, was ${aesIvMask.size}" }
        require(channels >= 1) { "channels must be >= 1" }
        require(rtpTimebase in 16_000..90_000) { "timeBase denominator out of range: $rtpTimebase" }
        require(RtpPayloadTypes.isValid(rtpPayloadType)) { "bad rtpPayloadType $rtpPayloadType" }
        require(ssrc in 1..0xFFFFFFFFL) { "ssrc out of range: $ssrc" }
        receiverRtcpDscp?.let { require(it in 0..63) { "receiverRtcpDscp must be in [0,63]" } }
        require(codecParameterIsValid(codecName, codecParameter)) {
            "codecParameter '$codecParameter' has the wrong prefix for codec '$codecName'"
        }
    }

    /** Writes the leading fields both dialects agree on, in the [profile]'s key order. */
    internal fun writeHeader(into: Json.Obj, profile: OfferProfile) {
        into.put("index", index)
        into.put("type", type.wire)
        into.put("codecName", codecName)
        if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) {
            // Gson emits Kotlin data-class fields in constructor order.
            into.put("ssrc", ssrc)
            into.put("rtpPayloadType", rtpPayloadType)
            into.put("rtpProfile", "cast")
            if (rtpExtensions.isNotEmpty()) into.put("rtpExtensions", Json.arrayOfStrings(rtpExtensions))
            into.put("receiverRtcpEventLog", receiverRtcpEventLog)
            into.put("targetDelay", targetDelayMs)
            if (profile.emitStoreTime) into.put("storeTime", storeTimeMs ?: targetDelayMs)
            into.put("timeBase", "1/$rtpTimebase")
            into.put("errorRecoveryMode", errorRecoveryMode)
        } else {
            if (profile.emitCodecParameter) into.put("codecParameter", codecParameter)
            // rtpProfile is technically required by the spec although it is always "cast";
            // libcast writes it unconditionally and never parses it back.
            into.put("rtpProfile", "cast")
            into.put("rtpPayloadType", rtpPayloadType)
            into.put("ssrc", ssrc)
            into.put("targetDelay", targetDelayMs)
            into.put("channels", channels)
            into.put("timeBase", "1/$rtpTimebase")
        }
    }

    /** Writes the trailing fields both dialects agree on, in the [profile]'s key order. */
    internal fun writeTrailer(into: Json.Obj, profile: OfferProfile) {
        if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) {
            into.put("aesIvMask", Bytes.toHex(aesIvMask))
            into.put("aesKey", Bytes.toHex(aesKey))
        } else {
            into.put("aesKey", Bytes.toHex(aesKey))
            into.put("aesIvMask", Bytes.toHex(aesIvMask))
            into.put("receiverRtcpEventLog", receiverRtcpEventLog)
            receiverRtcpDscp?.let { into.put("receiverRtcpDscp", it) }
            if (rtpExtensions.isNotEmpty()) into.put("rtpExtensions", Json.arrayOfStrings(rtpExtensions))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamCommon) return false
        return index == other.index && type == other.type && codecName == other.codecName &&
            rtpPayloadType == other.rtpPayloadType && ssrc == other.ssrc &&
            aesKey.contentEquals(other.aesKey) && aesIvMask.contentEquals(other.aesIvMask) &&
            rtpTimebase == other.rtpTimebase && codecParameter == other.codecParameter &&
            targetDelayMs == other.targetDelayMs && storeTimeMs == other.storeTimeMs &&
            channels == other.channels && errorRecoveryMode == other.errorRecoveryMode &&
            receiverRtcpEventLog == other.receiverRtcpEventLog &&
            receiverRtcpDscp == other.receiverRtcpDscp && rtpExtensions == other.rtpExtensions
    }

    override fun hashCode(): Int {
        var h = index
        h = 31 * h + type.hashCode()
        h = 31 * h + codecName.hashCode()
        h = 31 * h + rtpPayloadType
        h = 31 * h + ssrc.hashCode()
        h = 31 * h + aesKey.contentHashCode()
        h = 31 * h + aesIvMask.contentHashCode()
        h = 31 * h + rtpTimebase
        return h
    }

    companion object {
        /** RFC 6381 prefix each codec's `codecParameter` must start with. */
        private val CODEC_PARAMETER_PREFIX = mapOf(
            "h264" to "avc1",
            "vp8" to "vp08",
            "vp9" to "vp09",
            "av1" to "av01",
            "hevc" to "hev1",
            "aac" to "mp4a.",
        )

        /** True when [parameter] is empty or carries the right prefix for [codecName]. */
        fun codecParameterIsValid(codecName: String, parameter: String): Boolean {
            if (parameter.isEmpty()) return true
            val prefix = CODEC_PARAMETER_PREFIX[codecName] ?: return true
            return parameter.startsWith(prefix)
        }
    }
}

/** One stream in the OFFER's `supportedStreams` array. */
sealed class OfferStream {
    /** Fields shared by audio and video streams. */
    abstract val common: StreamCommon

    /** Serializes this stream in [profile]'s dialect. */
    abstract fun toJson(profile: OfferProfile): Json.Obj
}

/**
 * An `audio_source` stream.
 *
 * @property bitRate average bits/second. Required by the receiver's parser; a missing or
 *   non-integer value is a hard error. Both dialects send 128000.
 * @property sampleRate emitted by GMS as an explicit key (48000); openscreen conveys it only
 *   through `timeBase`. `null` omits the key.
 */
data class AudioOfferStream(
    override val common: StreamCommon,
    val bitRate: Int = StreamingConstants.DEFAULT_AUDIO_BITRATE,
    val sampleRate: Int? = null,
) : OfferStream() {
    init {
        require(common.type == StreamType.AUDIO_SOURCE) { "audio stream must have type audio_source" }
        require(bitRate >= 0) { "bitRate must be >= 0" }
    }

    override fun toJson(profile: OfferProfile): Json.Obj = jsonObj {
        common.writeHeader(this, profile)
        if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) {
            put("channels", common.channels)
            put("bitRate", bitRate)
            sampleRate?.let { put("sampleRate", it) }
        } else {
            put("bitRate", bitRate)
        }
        common.writeTrailer(this, profile)
    }
}

/**
 * A `video_source` stream.
 *
 * @property maxFrameRate rational frame rate as text: `"30"` or `"60000/1001"`.
 * @property maxBitRate maximum bits/second. Absent on the wire defaults to `4 << 20` receiver-side.
 * @property resolutions sender-supported resolutions; libcast's own sender requires at least one,
 *   each at least 320x240.
 * @property profile `""` for openscreen, `"main"` for GMS (which sends it even for VP9, where it
 *   is nominally meaningless).
 * @property level `""` for openscreen, `"4"` for GMS.
 * @property protection emitted as `""` by openscreen; omitted by GMS.
 * @property intraRefreshEnable GMS-only boolean, `!("castv2" == errorRecoveryMode)`, so false in
 *   practice. `null` omits the key.
 */
data class VideoOfferStream(
    override val common: StreamCommon,
    val resolutions: List<Resolution>,
    val maxFrameRate: String = "30",
    val maxBitRate: Int = StreamingConstants.DEFAULT_VIDEO_MAX_BITRATE,
    val profile: String = "",
    val level: String = "",
    val protection: String = "",
    val intraRefreshEnable: Boolean? = null,
) : OfferStream() {
    init {
        require(common.type == StreamType.VIDEO_SOURCE) { "video stream must have type video_source" }
        require(maxBitRate > 0) { "maxBitRate must be > 0" }
    }

    override fun toJson(profile: OfferProfile): Json.Obj = jsonObj {
        common.writeHeader(this, profile)
        if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) {
            put("profile", this@VideoOfferStream.profile)
            put("level", level)
            put("maxBitRate", maxBitRate)
            put("maxFrameRate", maxFrameRate)
            put("resolutions", Json.Arr(resolutions.map { it.toJson(heightFirst = true) as Json }.toMutableList()))
            if (profile.emitIntraRefreshEnable) {
                put("intraRefreshEnable", intraRefreshEnable ?: false)
            }
        } else {
            put("maxFrameRate", maxFrameRate)
            put("maxBitRate", maxBitRate)
            put("resolutions", Json.Arr(resolutions.map { it.toJson() as Json }.toMutableList()))
            put("profile", this@VideoOfferStream.profile)
            put("level", level)
            if (profile.emitProtection) put("protection", protection)
            put("errorRecoveryMode", common.errorRecoveryMode)
        }
        common.writeTrailer(this, profile)
    }
}

/**
 * The body of an `OFFER` message.
 *
 * @property streams every offered stream, audio first then video, with `index` starting at 0 and
 *   increasing by exactly 1.
 * @property profile which dialect to serialize in.
 * @property castMode always `"mirroring"` for a screen-mirroring sender. (`"remoting"` requires
 *   exactly one audio *and* one video stream and is a different feature entirely.)
 */
data class Offer(
    val streams: List<OfferStream>,
    val profile: OfferProfile = OfferProfile.DEFAULT,
    val castMode: String = CAST_MODE_MIRRORING,
) {
    init {
        require(streams.isNotEmpty()) { "an OFFER needs at least one stream" }
        streams.forEachIndexed { i, s ->
            require(s.common.index == i) {
                "stream indexes must start at 0 and increase by 1; stream $i has index ${s.common.index}"
            }
        }
        val ssrcs = streams.map { it.common.ssrc }
        require(ssrcs.toSet().size == ssrcs.size) { "duplicate SSRC in offer: $ssrcs" }
        val dscps = streams.map { it.common.receiverRtcpDscp }.toSet()
        require(dscps.size <= 1) { "mixed DSCP values in offer: $dscps" }
    }

    /** Serializes the `offer` body object. */
    fun toJson(): Json.Obj = jsonObj {
        if (profile.emitOfferUdpPort) put("udpPort", profile.offerUdpPort)
        put("supportedStreams", Json.Arr(streams.map { it.toJson(profile) as Json }.toMutableList()))
        put("castMode", castMode)
    }

    /** Wraps this offer in the full `{"type":"OFFER","seqNum":..,"offer":{..}}` envelope. */
    fun toMessage(seqNum: Int): Json.Obj = jsonObj {
        put("type", StreamingMessageTypes.OFFER)
        put("seqNum", seqNum)
        put("offer", toJson())
    }

    /** The stream at OFFER `index` [i], or `null`. */
    fun streamAtIndex(i: Int): OfferStream? = streams.firstOrNull { it.common.index == i }

    /** The first video stream, or `null` for an audio-only offer. */
    fun firstVideoStream(): VideoOfferStream? = streams.filterIsInstance<VideoOfferStream>().firstOrNull()

    companion object {
        /** The only `castMode` a mirroring sender ever sends. */
        const val CAST_MODE_MIRRORING = "mirroring"

        /** The media-remoting `castMode`. Not used by this implementation. */
        const val CAST_MODE_REMOTING = "remoting"

        private val random = SecureRandom()

        /** Generates 16 cryptographically random bytes for an `aesKey` or `aesIvMask`. */
        fun randomKeyBytes(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        /**
         * Generates an SSRC in the priority range appropriate for the stream kind.
         *
         * Audio uses the "higher priority" range `[1, 50000]` and video the "normal priority"
         * range `[50001, 100000]`; the packet router sorts senders by SSRC so audio packets go
         * out before video within a burst.
         *
         * @param exclude already-allocated SSRCs to avoid.
         */
        fun generateSsrc(audio: Boolean, exclude: Set<Long> = emptySet()): Long {
            val range = if (audio) StreamingConstants.AUDIO_SSRC_RANGE else StreamingConstants.VIDEO_SSRC_RANGE
            var candidate: Long
            do {
                candidate = (range.first + random.nextInt(range.last - range.first + 1)).toLong()
            } while (candidate in exclude)
            return candidate
        }

        /** GMS's fixed audio SSRC. */
        const val GMS_AUDIO_SSRC = 1L

        /** GMS's fixed SSRC for the first video stream; the second gets 12, and so on. */
        const val GMS_FIRST_VIDEO_SSRC = 11L
    }
}

/**
 * Builds the OFFER for a given [OfferProfile].
 *
 * Audio streams come first and indexes are assigned automatically, so a video-only offer is
 * byte-identical to the audio+video one minus the index-0 stream, with video shifted down to
 * index 0. That matters on GrapheneOS: system audio cannot be captured the way GMS captures it
 * (`REMOTE_SUBMIX` needs `CAPTURE_AUDIO_OUTPUT`, the `AudioPolicy` route needs
 * `MODIFY_AUDIO_ROUTING`, both signature|privileged), so video-only is the realistic default even
 * though GMS always offers audio.
 */
object OfferBuilder {

    /**
     * Builds a mirroring offer.
     *
     * @param profile which dialect to build. Every other parameter defaults from it.
     * @param videoCodecs video codecs to offer, most-preferred first.
     * @param audioCodecs audio codecs to offer; empty for a video-only offer.
     * @param resolutions offered capture resolutions.
     * @param maxFrameRate rational frame rate string.
     * @param maxBitRate video bitrate ceiling.
     * @param audioBitRate audio bitrate.
     * @param targetDelayMs end-to-end playout delay.
     * @param androidTvPayloadTypeHack force the Android-TV quirk payload types. Ignored by the
     *   GMS profile, which always uses them; this is the openscreen profile's runtime fallback.
     * @param receiverRtcpDscp DSCP to request, or `null` to omit the key entirely.
     * @param receiverRtcpEventLog whether to request RTCP event logs.
     * @param aesKey/@param aesIvMask override the generated key material (tests, reproducibility).
     */
    fun build(
        profile: OfferProfile = OfferProfile.DEFAULT,
        videoCodecs: List<String> = profile.videoCodecs,
        audioCodecs: List<String> = emptyList(),
        resolutions: List<Resolution> = profile.resolutions,
        maxFrameRate: String = profile.maxFrameRate,
        maxBitRate: Int = profile.maxBitRate,
        audioBitRate: Int = profile.audioBitRate,
        targetDelayMs: Int = profile.targetDelayMs,
        androidTvPayloadTypeHack: Boolean = false,
        receiverRtcpDscp: Int? = null,
        receiverRtcpEventLog: Boolean = profile.receiverRtcpEventLog,
        aesKey: ByteArray? = null,
        aesIvMask: ByteArray? = null,
    ): Offer {
        require(videoCodecs.isNotEmpty() || audioCodecs.isNotEmpty()) {
            "need at least one audio or video config to negotiate"
        }
        val gms = profile == OfferProfile.GMS_SYSTEM_MIRRORING
        // GMS reuses one (key, IV) pair across every stream; openscreen generates a fresh pair
        // per stream. Both are regenerated per session.
        val sharedKey = aesKey ?: Offer.randomKeyBytes()
        val sharedIv = aesIvMask ?: Offer.randomKeyBytes()
        fun keyFor(): Pair<ByteArray, ByteArray> =
            if (profile.sharedKeys) sharedKey to sharedIv
            else (aesKey ?: Offer.randomKeyBytes()) to (aesIvMask ?: Offer.randomKeyBytes())

        val streams = ArrayList<OfferStream>()
        val used = HashSet<Long>()
        var index = 0

        for ((n, codec) in audioCodecs.withIndex()) {
            val ssrc =
                if (profile.fixedSsrcs) Offer.GMS_AUDIO_SSRC + n
                else Offer.generateSsrc(audio = true, exclude = used)
            used.add(ssrc)
            val (k, iv) = keyFor()
            // GMS uses payload type 127 for audio unconditionally -- that is exactly what
            // openscreen calls the "Android TV hack", and it is what every Android phone sends.
            val pt = RtpPayloadTypes.forCodec(
                codec,
                androidTvHack = profile.androidTvPayloadTypes || androidTvPayloadTypeHack,
            )
            streams.add(
                AudioOfferStream(
                    common = StreamCommon(
                        index = index++,
                        type = StreamType.AUDIO_SOURCE,
                        codecName = codec,
                        rtpPayloadType = pt,
                        ssrc = ssrc,
                        aesKey = k,
                        aesIvMask = iv,
                        rtpTimebase = StreamingConstants.AUDIO_TIMEBASE,
                        targetDelayMs = targetDelayMs,
                        storeTimeMs = if (profile.emitStoreTime) targetDelayMs else null,
                        errorRecoveryMode = profile.errorRecoveryMode,
                        receiverRtcpEventLog = receiverRtcpEventLog,
                        receiverRtcpDscp = receiverRtcpDscp,
                        rtpExtensions = profile.rtpExtensions,
                    ),
                    bitRate = audioBitRate,
                    sampleRate = if (gms) StreamingConstants.AUDIO_TIMEBASE else null,
                )
            )
        }

        for ((n, codec) in videoCodecs.withIndex()) {
            val ssrc =
                if (profile.fixedSsrcs) Offer.GMS_FIRST_VIDEO_SSRC + n
                else Offer.generateSsrc(audio = false, exclude = used)
            used.add(ssrc)
            val (k, iv) = keyFor()
            // GMS's payload types are positional, not per-codec: the FIRST video stream gets 96
            // (the Android-TV hack value) and any second video stream gets its canonical value.
            val pt = when {
                (profile.androidTvPayloadTypes || androidTvPayloadTypeHack) && n == 0 ->
                    RtpPayloadTypes.VIDEO_HACK_FOR_ANDROID_TV
                gms -> RtpPayloadTypes.forCodecGms(codec)
                else -> RtpPayloadTypes.forCodec(codec, androidTvHack = androidTvPayloadTypeHack)
            }
            streams.add(
                VideoOfferStream(
                    common = StreamCommon(
                        index = index++,
                        type = StreamType.VIDEO_SOURCE,
                        codecName = codec,
                        rtpPayloadType = pt,
                        ssrc = ssrc,
                        aesKey = k,
                        aesIvMask = iv,
                        rtpTimebase = StreamingConstants.VIDEO_TIMEBASE,
                        targetDelayMs = targetDelayMs,
                        storeTimeMs = if (profile.emitStoreTime) targetDelayMs else null,
                        errorRecoveryMode = profile.errorRecoveryMode,
                        receiverRtcpEventLog = receiverRtcpEventLog,
                        receiverRtcpDscp = receiverRtcpDscp,
                        rtpExtensions = profile.rtpExtensions,
                    ),
                    resolutions = resolutions,
                    maxFrameRate = maxFrameRate,
                    maxBitRate = maxBitRate,
                    profile = profile.videoProfile,
                    level = profile.videoLevel,
                    intraRefreshEnable = if (profile.emitIntraRefreshEnable) {
                        profile.errorRecoveryMode != "castv2"
                    } else null,
                )
            )
        }
        return Offer(streams, profile)
    }
}
