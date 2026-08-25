package app.castmirror.core.cast.streaming.rtp

/**
 * RTP wire constants and payload types.
 *
 * Transcribed from `openscreen:cast/streaming/impl/rtp_defines.h`.
 */
object RtpDefines {
    /** Byte 0 of every Cast RTP packet: V=2, P=0, X=0, CC=0. Always exactly `0x80`. */
    const val REQUIRED_FIRST_BYTE = 0x80

    /** Byte 1 bit 7: set on the **last** packet of a frame (not RTP's usual marker semantics). */
    const val MARKER_BIT_MASK = 0x80

    /** Byte 1 bits 0-6: payload type. */
    const val PAYLOAD_TYPE_MASK = 0x7F

    /** Byte 12 bit 7: this frame is a key frame. */
    const val KEY_FRAME_BIT_MASK = 0x80

    /** Byte 12 bit 6: a reference-frame-id byte follows. Always set by this implementation. */
    const val HAS_REFERENCE_FRAME_ID_BIT_MASK = 0x40

    /** Byte 12 bits 0-5: number of Cast header extensions. */
    const val EXTENSION_COUNT_MASK = 0x3F

    /** 12 RTP bytes + 6 Cast bytes, before the always-present reference-frame-id byte. */
    const val RTP_PACKET_MIN_VALID_SIZE = 18

    /** 19: [RTP_PACKET_MIN_VALID_SIZE] plus the always-present reference-frame-id byte. */
    const val BASE_RTP_HEADER_SIZE = RTP_PACKET_MIN_VALID_SIZE + 1

    /** Size of the adaptive-latency extension: two big-endian uint16s. */
    const val ADAPTIVE_LATENCY_HEADER_SIZE = 4

    /**
     * 23. libcast **always** reserves the adaptive-latency bytes when sizing payload chunks, even
     * on the overwhelming majority of packets that do not carry the extension, so that
     * `computeNumberOfPackets` agrees between the packet-0 and packet-N paths.
     */
    const val MAX_RTP_HEADER_SIZE = BASE_RTP_HEADER_SIZE + ADAPTIVE_LATENCY_HEADER_SIZE

    /** Adaptive-latency extension type id. */
    const val ADAPTIVE_LATENCY_EXTENSION_TYPE = 1

    /** Width of the extension "data size" field, in bits. */
    const val NUM_EXTENSION_DATA_SIZE_FIELD_BITS = 10

    /**
     * First half-word of the adaptive-latency extension:
     * `(type << 10) | sizeof(uint16_t)` == `(1 << 10) | 2` == `0x0402`.
     */
    const val ADAPTIVE_LATENCY_FIRST_WORD =
        (ADAPTIVE_LATENCY_EXTENSION_TYPE shl NUM_EXTENSION_DATA_SIZE_FIELD_BITS) or 2

    /** Sentinel packet id in a NACK meaning "no packet of this frame arrived". */
    const val ALL_PACKETS_LOST = 0xFFFF

    /** Highest usable packet id, because [ALL_PACKETS_LOST] is reserved. */
    const val MAX_ALLOWED_FRAME_PACKET_ID = ALL_PACKETS_LOST - 1
}

/**
 * RTP payload types.
 *
 * The payload type is informational — the authoritative codec signal is `codecName` in the
 * OFFER — but the receiver uses it to route a packet to its audio or video pipeline, so it must
 * agree between the OFFER and the RTP header.
 */
object RtpPayloadTypes {
    /** Opus audio. */
    const val AUDIO_OPUS = 96

    /** AAC audio. */
    const val AUDIO_AAC = 97

    /** 16-bit PCM audio. */
    const val AUDIO_PCM16 = 98

    /** Unspecified/remote audio. */
    const val AUDIO_VARIOUS = 99

    /** VP8 video (shared with VP9). */
    const val VIDEO_VP8 = 100

    /** H.264 video (shared with HEVC). */
    const val VIDEO_H264 = 101

    /** Unspecified/remote video. */
    const val VIDEO_VARIOUS = 102

    /**
     * VP9 video — **but openscreen and GMS disagree**, so pick by profile.
     *
     * `rtp_defines.h` names the constant `kVideoVp9 = 103`, yet
     * `GetPayloadType(VideoCodec::kVp9)` in `rtp_defines.cc` deliberately returns `kVideoVp8`
     * (100) with the comment "VP8 and VP9 share the same payload type". GMS, on the other hand,
     * sends **103** for its second video stream. Use [forCodec] for the openscreen dialect and
     * [forCodecGms] for the GMS dialect.
     */
    const val VIDEO_VP9_CANONICAL = 103

    /** What openscreen's `GetPayloadType` actually returns for VP9: VP8's value. */
    const val VIDEO_VP9_OPENSCREEN = VIDEO_VP8

    /** AV1 video. */
    const val VIDEO_AV1 = 104

    /**
     * Audio payload type demanded by some Android-TV receivers regardless of codec.
     *
     * SPEC-UNVERIFIED: which firmwares still require this is unknown — Google's own TODO
     * (issuetracker.google.com/184438154) says they have lost track (spec §3.3, §10.2, §11 item 3).
     */
    const val AUDIO_HACK_FOR_ANDROID_TV = 127

    /**
     * Video payload type demanded by some Android-TV receivers regardless of codec.
     *
     * Note the collision with [AUDIO_OPUS]: with the hack enabled, audio/video routing depends on
     * the flag being set consistently on both ends, which is why openscreen keeps
     * `use_android_rtp_hack` as one session-wide boolean.
     *
     * SPEC-UNVERIFIED: see [AUDIO_HACK_FOR_ANDROID_TV].
     */
    const val VIDEO_HACK_FOR_ANDROID_TV = 96

    /** Audio payload types accepted by `IsRtpPayloadType`. */
    val AUDIO_RANGE = AUDIO_OPUS..AUDIO_VARIOUS

    /** Video payload types accepted by `IsRtpPayloadType`. */
    val VIDEO_RANGE = VIDEO_VP8..VIDEO_AV1

    /** True if [pt] is a payload type libcast would accept in an OFFER. */
    fun isValid(pt: Int): Boolean =
        pt in AUDIO_RANGE || pt in VIDEO_RANGE || pt == AUDIO_HACK_FOR_ANDROID_TV

    /**
     * Maps a codec name to its payload type.
     *
     * @param codecName one of the OFFER `codecName` values.
     * @param androidTvHack when true, returns the Android-TV quirk values instead.
     */
    fun forCodec(codecName: String, androidTvHack: Boolean = false): Int {
        val audio = codecName in AUDIO_CODECS
        if (androidTvHack) return if (audio) AUDIO_HACK_FOR_ANDROID_TV else VIDEO_HACK_FOR_ANDROID_TV
        return when (codecName) {
            "opus" -> AUDIO_OPUS
            "aac" -> AUDIO_AAC
            "REMOTE_AUDIO" -> AUDIO_VARIOUS
            "vp8", "vp9" -> VIDEO_VP8
            "h264", "hevc" -> VIDEO_H264
            "av1" -> VIDEO_AV1
            "REMOTE_VIDEO" -> VIDEO_VARIOUS
            else -> throw IllegalArgumentException("unknown codec name '$codecName'")
        }
    }

    /**
     * Maps a codec name to the payload type **GMS** would use for a non-primary stream.
     *
     * GMS's assignment is positional rather than per-codec: the first audio stream gets 127 and
     * the first video stream gets 96 regardless of codec (see
     * [AUDIO_HACK_FOR_ANDROID_TV] / [VIDEO_HACK_FOR_ANDROID_TV]). Its *second* video stream —
     * VP9, or HEVC behind the phone-to-phone flag — is hard-coded to 103, i.e. openscreen's
     * `kVideoVp9` constant rather than the value `GetPayloadType` computes.
     */
    fun forCodecGms(codecName: String): Int = when (codecName) {
        "vp9", "hevc" -> VIDEO_VP9_CANONICAL
        else -> forCodec(codecName)
    }

    /** Audio codec names accepted in an OFFER (`message_fields.cc:16-27`). */
    val AUDIO_CODECS = setOf("opus", "aac", "REMOTE_AUDIO")

    /** Video codec names accepted in an OFFER (`message_fields.cc:16-27`). */
    val VIDEO_CODECS = setOf("h264", "vp8", "vp9", "hevc", "av1", "REMOTE_VIDEO")
}
