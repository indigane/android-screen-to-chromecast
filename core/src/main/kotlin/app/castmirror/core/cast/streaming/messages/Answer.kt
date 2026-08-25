package app.castmirror.core.cast.streaming.messages

import app.castmirror.core.util.Json

/** Audio constraints reported by the receiver in `answer.constraints.audio`. */
data class AudioConstraints(
    /** Maximum sample rate the receiver can decode. */
    val maxSampleRate: Int,
    /** Maximum channel count. */
    val maxChannels: Int,
    /** Maximum bitrate. */
    val maxBitRate: Int,
    /** Minimum bitrate; defaults to 32000. */
    val minBitRate: Int = 32_000,
    /** Maximum tolerated playout delay in milliseconds. */
    val maxDelayMs: Int? = null,
) {
    /** libcast's validity rule for this object. */
    fun isValid(): Boolean =
        maxSampleRate > 0 && maxChannels > 0 && minBitRate > 0 && maxBitRate >= minBitRate

    companion object {
        /** Parses `constraints.audio`, or `null` if a required key is missing. */
        fun parse(o: Json.Obj): AudioConstraints? {
            val maxSampleRate = o["maxSampleRate"]?.asIntOrNull() ?: return null
            val maxChannels = o["maxChannels"]?.asIntOrNull() ?: return null
            val maxBitRate = o["maxBitRate"]?.asIntOrNull() ?: return null
            return AudioConstraints(
                maxSampleRate = maxSampleRate,
                maxChannels = maxChannels,
                maxBitRate = maxBitRate,
                minBitRate = o["minBitRate"]?.asIntOrNull() ?: 32_000,
                maxDelayMs = o["maxDelay"]?.asIntOrNull(),
            )
        }
    }
}

/** A `{width,height,frameRate}` triple. */
data class Dimensions(
    /** Width in pixels. */
    val width: Int,
    /** Height in pixels. */
    val height: Int,
    /** Rational frame rate as text, e.g. `"30"` or `"30000/1001"`. */
    val frameRate: String? = null,
) {
    /** The frame rate evaluated as a double, or `null` when absent/unparseable. */
    fun frameRateValue(): Double? {
        val s = frameRate ?: return null
        val slash = s.indexOf('/')
        return if (slash < 0) s.toDoubleOrNull()
        else {
            val n = s.substring(0, slash).trim().toDoubleOrNull() ?: return null
            val d = s.substring(slash + 1).trim().toDoubleOrNull() ?: return null
            if (d == 0.0) null else n / d
        }
    }

    companion object {
        /** Parses a dimensions object; returns `null` when width or height is missing. */
        fun parse(o: Json.Obj): Dimensions? {
            val w = o["width"]?.asIntOrNull() ?: return null
            val h = o["height"]?.asIntOrNull() ?: return null
            return Dimensions(w, h, o["frameRate"]?.asStringOrNull())
        }
    }
}

/** Video constraints reported by the receiver in `answer.constraints.video`. */
data class VideoConstraints(
    /** Largest resolution/frame rate the receiver accepts. */
    val maxDimensions: Dimensions,
    /** Maximum bitrate. */
    val maxBitRate: Int,
    /** Minimum bitrate; defaults to 300000. */
    val minBitRate: Int = 300_000,
    /** Smallest resolution the receiver accepts, when reported. */
    val minResolution: Resolution? = null,
    /**
     * Pixel-rate ceiling. Usually **more restrictive** than `maxDimensions`, and the knob that
     * decides "1280x720@60 or 1920x1080@30".
     */
    val maxPixelsPerSecond: Double? = null,
    /** Maximum tolerated playout delay in milliseconds. */
    val maxDelayMs: Int? = null,
) {
    /** libcast's validity rule: note `maxBitRate > minBitRate` is **strict**. */
    fun isValid(): Boolean =
        maxBitRate > minBitRate && (maxDimensions.frameRateValue() ?: 1.0) > 0.0

    companion object {
        /** Parses `constraints.video`, or `null` if a required key is missing. */
        fun parse(o: Json.Obj): VideoConstraints? {
            val dims = o["maxDimensions"]?.asObjOrNull()?.let(Dimensions::parse) ?: return null
            val maxBitRate = o["maxBitRate"]?.asIntOrNull() ?: return null
            return VideoConstraints(
                maxDimensions = dims,
                maxBitRate = maxBitRate,
                minBitRate = o["minBitRate"]?.asIntOrNull() ?: 300_000,
                minResolution = o["minResolution"]?.asObjOrNull()?.let(Resolution::parse),
                maxPixelsPerSecond = o["maxPixelsPerSecond"]?.asDoubleOrNull(),
                maxDelayMs = o["maxDelay"]?.asIntOrNull(),
            )
        }
    }
}

/** Both halves of `answer.constraints`. */
data class Constraints(
    /** Audio limits. */
    val audio: AudioConstraints,
    /** Video limits. */
    val video: VideoConstraints,
)

/** `answer.display` — the receiver's panel description. */
data class DisplayDescription(
    /** The panel's actual size; encode to this, not larger. */
    val dimensions: Dimensions? = null,
    /** `"W:H"`, e.g. `"16:9"`. */
    val aspectRatio: String? = null,
    /** `"sender"` means *you* must letterbox; `"receiver"` means any size is fine. */
    val scaling: String? = null,
) {
    /** At least one property must be set for the object to be meaningful. */
    fun isValid(): Boolean {
        if (dimensions == null && aspectRatio == null && scaling == null) return false
        // scaling:"sender" requires dimensions or aspectRatio to also be present.
        if (scaling == SCALING_SENDER && dimensions == null && aspectRatio == null) return false
        return true
    }

    companion object {
        /** The sender must letterbox to the given aspect ratio. */
        const val SCALING_SENDER = "sender"

        /** The receiver will scale; send any size. */
        const val SCALING_RECEIVER = "receiver"

        /** Parses `answer.display`. */
        fun parse(o: Json.Obj): DisplayDescription = DisplayDescription(
            dimensions = o["dimensions"]?.asObjOrNull()?.let(Dimensions::parse),
            aspectRatio = o["aspectRatio"]?.asStringOrNull(),
            scaling = o["scaling"]?.asStringOrNull(),
        )
    }
}

/**
 * A successfully parsed `ANSWER` body.
 *
 * @property udpPort the receiver UDP port carrying **all** RTP and RTCP for **all** streams in
 *   this session — audio and video share one socket.
 * @property sendIndexes the OFFER `index` values the receiver accepted.
 * @property ssrcs the **receiver's** SSRCs, one per [sendIndexes] entry, in the same order.
 *   These go in the "SSRC of Receiver" field of the RTCP packets you build. The reference
 *   receiver uses `offered_ssrc + 1`, but never rely on that.
 * @property receiverRtcpEventLog indexes for which the receiver will send event logs. Absent
 *   means the receiver does not support them at all.
 * @property receiverRtcpDscp indexes for which the receiver honours your DSCP request. libcast
 *   enables DSCP only when this set exactly equals [sendIndexes] — support is all or nothing.
 * @property rtpExtensions per-stream enabled extensions, same order as [sendIndexes].
 * @property constraints receiver capability limits, when reported and valid.
 * @property display the receiver panel description, when reported.
 * @property ipaddr the address the receiver wants media sent to. Not in openscreen's
 *   `answer_messages.cc`, but GMS reads it and lets it **override** the control-channel address
 *   for the UDP destination, so a receiver on a second interface reaches you correctly.
 */
data class Answer(
    val udpPort: Int,
    val sendIndexes: List<Int>,
    val ssrcs: List<Long>,
    val receiverRtcpEventLog: List<Int> = emptyList(),
    val receiverRtcpDscp: List<Int> = emptyList(),
    val rtpExtensions: List<List<String>> = emptyList(),
    val constraints: Constraints? = null,
    val display: DisplayDescription? = null,
    val ipaddr: String? = null,
) {
    /**
     * The resolution to actually encode at, given what was offered.
     *
     * GMS's rule (`beho.c`): when `display.dimensions` is smaller than the offered resolution in
     * **both** axes, it replaces `resolutions[0]`. A mixed case — narrower but taller, say — is
     * ignored rather than guessed at.
     *
     * @param offered the resolution that was put in the OFFER.
     */
    fun effectiveResolution(offered: Resolution): Resolution {
        val d = display?.dimensions ?: return offered
        return if (d.width < offered.width && d.height < offered.height) Resolution(d.width, d.height)
        else offered
    }

    /** The receiver SSRC paired with OFFER index [index], or `null` if it was not selected. */
    fun receiverSsrcForIndex(index: Int): Long? {
        val at = sendIndexes.indexOf(index)
        return if (at >= 0 && at < ssrcs.size) ssrcs[at] else null
    }

    /** True when the receiver honours the offered DSCP for every selected stream. */
    fun dscpEnabled(): Boolean =
        receiverRtcpDscp.isNotEmpty() && receiverRtcpDscp.toSet() == sendIndexes.toSet()
}

/** The `error` body of a `result: "error"` reply. */
data class ReceiverError(
    /** Opaque numeric code; receivers use their own numbering. Log, do not branch on it. */
    val code: Int,
    /** Human-readable description. This is the useful half. */
    val description: String,
) {
    companion object {
        /** Parses an `error` object. */
        fun parse(o: Json.Obj): ReceiverError = ReceiverError(
            code = o["code"]?.asIntOrNull() ?: 0,
            description = o["description"]?.asStringOrNull() ?: "",
        )
    }
}

/** The outcome of parsing an inbound `ANSWER`. */
sealed class AnswerResult {
    /** `result: "ok"` with a structurally valid body. */
    data class Ok(
        /** The parsed answer. */
        val answer: Answer,
    ) : AnswerResult()

    /** `result: "error"` — the receiver refused the offer. */
    data class Error(
        /** The receiver's error body. */
        val error: ReceiverError,
    ) : AnswerResult()

    /** The message could not be parsed as an ANSWER at all. */
    data class Invalid(
        /** Why parsing failed. */
        val reason: String,
    ) : AnswerResult()
}

/** Parser for `ANSWER` messages. */
object AnswerParser {

    /**
     * Parses a full `{"type":"ANSWER", ...}` envelope.
     *
     * Validation follows libcast, with one deliberate relaxation noted in the spec's error table:
     * a `constraints` object that is present but invalid is **logged and dropped** rather than
     * taking the whole ANSWER down, because the defaults are perfectly usable.
     */
    fun parse(message: Json.Obj): AnswerResult {
        val type = message["type"]?.asStringOrNull()?.uppercase()
        if (type != "ANSWER") return AnswerResult.Invalid("not an ANSWER message (type=$type)")

        val seqNum = message["seqNum"]?.asIntOrNull()
        if (seqNum == null || seqNum < 0) return AnswerResult.Invalid("missing or negative seqNum")

        when (message["result"]?.asStringOrNull()) {
            "error" -> {
                val err = message["error"]?.asObjOrNull()
                    ?: return AnswerResult.Invalid("result=error without an error body")
                return AnswerResult.Error(ReceiverError.parse(err))
            }
            "ok", null -> Unit
            else -> return AnswerResult.Invalid("unknown result '${message["result"]?.encode()}'")
        }

        val body = message["answer"]?.asObjOrNull()
            ?: return AnswerResult.Invalid("ANSWER has no answer body")
        return parseBody(body)
    }

    /** Parses just the `answer` body object. */
    fun parseBody(body: Json.Obj): AnswerResult {
        val udpPort = body["udpPort"]?.asIntOrNull()
            ?: return AnswerResult.Invalid("answer.udpPort missing or not an integer")
        if (udpPort !in 1..65535) return AnswerResult.Invalid("answer.udpPort $udpPort out of range")

        val sendIndexesJson = body["sendIndexes"]?.asArrOrNull()
            ?: return AnswerResult.Invalid("answer.sendIndexes missing or not an array")
        val sendIndexes = ArrayList<Int>(sendIndexesJson.size)
        for (v in sendIndexesJson.items) {
            val i = v.asIntOrNull() ?: return AnswerResult.Invalid("non-integer in sendIndexes")
            if (i < 0) return AnswerResult.Invalid("negative sendIndexes entry $i")
            sendIndexes.add(i)
        }

        val ssrcsJson = body["ssrcs"]?.asArrOrNull()
            ?: return AnswerResult.Invalid("answer.ssrcs missing or not an array")
        val ssrcs = ArrayList<Long>(ssrcsJson.size)
        for (v in ssrcsJson.items) {
            val s = v.asLongOrNull() ?: return AnswerResult.Invalid("non-integer in ssrcs")
            if (s < 0 || s > 0xFFFFFFFFL) return AnswerResult.Invalid("ssrc $s out of uint32 range")
            ssrcs.add(s)
        }
        if (ssrcs.size != sendIndexes.size) {
            return AnswerResult.Invalid("ssrcs (${ssrcs.size}) and sendIndexes (${sendIndexes.size}) differ in length")
        }
        if (sendIndexes.isEmpty()) {
            return AnswerResult.Invalid("no stream selected (empty sendIndexes)")
        }

        // "Absent" and "empty" must be treated identically: libcast omits these keys entirely
        // when empty because some senders mishandle empty arrays.
        val eventLog = intList(body["receiverRtcpEventLog"])
        val dscp = intList(body["receiverRtcpDscp"])
        val extensions = body["rtpExtensions"]?.asArrOrNull()?.items?.map { outer ->
            outer.asArrOrNull()?.items?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        } ?: emptyList()

        val constraints = body["constraints"]?.asObjOrNull()?.let { c ->
            val a = c["audio"]?.asObjOrNull()?.let(AudioConstraints::parse)
            val v = c["video"]?.asObjOrNull()?.let(VideoConstraints::parse)
            if (a != null && v != null && a.isValid() && v.isValid()) Constraints(a, v) else null
        }

        val display = body["display"]?.asObjOrNull()
            ?.let(DisplayDescription::parse)
            ?.takeIf { it.isValid() }

        return AnswerResult.Ok(
            Answer(
                udpPort = udpPort,
                sendIndexes = sendIndexes,
                ssrcs = ssrcs,
                receiverRtcpEventLog = eventLog,
                receiverRtcpDscp = dscp,
                rtpExtensions = extensions,
                constraints = constraints,
                display = display,
                ipaddr = body["ipaddr"]?.asStringOrNull()?.takeIf { it.isNotEmpty() },
            )
        )
    }

    private fun intList(v: Json?): List<Int> =
        v?.asArrOrNull()?.items?.mapNotNull { it.asIntOrNull() } ?: emptyList()
}
