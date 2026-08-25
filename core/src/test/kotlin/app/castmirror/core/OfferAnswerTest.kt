package app.castmirror.core

import app.castmirror.core.cast.streaming.messages.AnswerParser
import app.castmirror.core.cast.streaming.messages.AnswerResult
import app.castmirror.core.cast.streaming.messages.AudioOfferStream
import app.castmirror.core.cast.streaming.messages.DisplayDescription
import app.castmirror.core.cast.streaming.messages.Offer
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.messages.OfferBuilder
import app.castmirror.core.cast.streaming.messages.Resolution
import app.castmirror.core.cast.streaming.messages.StreamCommon
import app.castmirror.core.cast.streaming.messages.StreamType
import app.castmirror.core.cast.streaming.messages.VideoOfferStream
import app.castmirror.core.cast.streaming.rtp.RtpPayloadTypes
import app.castmirror.core.util.Bytes
import app.castmirror.core.util.Json

/**
 * The OFFER example from spec section 4.2, verbatim. The generated OFFER is compared against this
 * key by key, which is the check that actually matters: JSON key *order* is irrelevant on the
 * wire (libcast's jsoncpp emits keys in sorted order anyway), but a missing, extra or
 * wrongly-typed key takes the whole offer down.
 */
private const val SPEC_OFFER = """
{
  "type": "OFFER",
  "seqNum": 1,
  "offer": {
    "castMode": "mirroring",
    "supportedStreams": [
      {
        "index": 0,
        "type": "audio_source",
        "codecName": "opus",
        "codecParameter": "",
        "rtpProfile": "cast",
        "rtpPayloadType": 96,
        "ssrc": 19088,
        "targetDelay": 400,
        "channels": 2,
        "bitRate": 128000,
        "timeBase": "1/48000",
        "aesKey": "51027e4e2347cbcb49d57ef10177aeb9",
        "aesIvMask": "7f12a19be62a36c04ae4116caaeff6d1",
        "receiverRtcpEventLog": true,
        "receiverRtcpDscp": 34
      },
      {
        "index": 1,
        "type": "video_source",
        "codecName": "h264",
        "codecParameter": "avc1.42E01F",
        "rtpProfile": "cast",
        "rtpPayloadType": 101,
        "ssrc": 62811,
        "targetDelay": 400,
        "channels": 1,
        "timeBase": "1/90000",
        "maxFrameRate": "30",
        "maxBitRate": 5000000,
        "resolutions": [{"width": 1920, "height": 1080}],
        "profile": "",
        "level": "",
        "protection": "",
        "errorRecoveryMode": "",
        "aesKey": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
        "aesIvMask": "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
        "receiverRtcpEventLog": true,
        "receiverRtcpDscp": 34
      },
      {
        "index": 2,
        "type": "video_source",
        "codecName": "vp8",
        "codecParameter": "",
        "rtpProfile": "cast",
        "rtpPayloadType": 100,
        "ssrc": 62812,
        "targetDelay": 400,
        "channels": 1,
        "timeBase": "1/90000",
        "maxFrameRate": "30",
        "maxBitRate": 5000000,
        "resolutions": [{"width": 1920, "height": 1080}],
        "profile": "",
        "level": "",
        "protection": "",
        "errorRecoveryMode": "",
        "aesKey": "99887766554433221100112233445566",
        "aesIvMask": "ffeeddccbbaa99887766554433221100",
        "receiverRtcpEventLog": true,
        "receiverRtcpDscp": 34
      }
    ]
  }
}
"""

/** The ANSWER example from spec section 4.4. */
private const val SPEC_ANSWER = """
{
  "type": "ANSWER",
  "seqNum": 1,
  "result": "ok",
  "answer": {
    "udpPort": 33533,
    "sendIndexes": [0, 1],
    "ssrcs": [19089, 62812],
    "receiverRtcpEventLog": [0, 1],
    "receiverRtcpDscp": [0, 1],
    "rtpExtensions": [[], []],
    "constraints": {
      "audio": {
        "maxSampleRate": 48000,
        "maxChannels": 2,
        "minBitRate": 32000,
        "maxBitRate": 320000,
        "maxDelay": 800
      },
      "video": {
        "maxDimensions": {"width": 1920, "height": 1080, "frameRate": "30"},
        "minBitRate": 300000,
        "maxBitRate": 10000000,
        "maxPixelsPerSecond": 62208000,
        "minResolution": {"width": 320, "height": 180},
        "maxDelay": 800
      }
    },
    "display": {
      "dimensions": {"width": 1920, "height": 1080, "frameRate": "60"},
      "aspectRatio": "16:9",
      "scaling": "receiver"
    }
  }
}
"""

/** Rebuilds the spec's OFFER exactly, using the same keys and SSRCs. */
private fun buildSpecOffer(): Offer {
    val audio = AudioOfferStream(
        common = StreamCommon(
            index = 0,
            type = StreamType.AUDIO_SOURCE,
            codecName = "opus",
            rtpPayloadType = 96,
            ssrc = 19088,
            aesKey = Bytes.fromHex("51027e4e2347cbcb49d57ef10177aeb9"),
            aesIvMask = Bytes.fromHex("7f12a19be62a36c04ae4116caaeff6d1"),
            rtpTimebase = 48_000,
            channels = 2,
            receiverRtcpEventLog = true,
            receiverRtcpDscp = 34,
        ),
        bitRate = 128_000,
    )
    val h264 = VideoOfferStream(
        common = StreamCommon(
            index = 1,
            type = StreamType.VIDEO_SOURCE,
            codecName = "h264",
            codecParameter = "avc1.42E01F",
            rtpPayloadType = 101,
            ssrc = 62811,
            aesKey = Bytes.fromHex("a1b2c3d4e5f60718293a4b5c6d7e8f90"),
            aesIvMask = Bytes.fromHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0"),
            rtpTimebase = 90_000,
            receiverRtcpEventLog = true,
            receiverRtcpDscp = 34,
        ),
        resolutions = listOf(Resolution(1920, 1080)),
        maxBitRate = 5_000_000,
    )
    val vp8 = VideoOfferStream(
        common = StreamCommon(
            index = 2,
            type = StreamType.VIDEO_SOURCE,
            codecName = "vp8",
            rtpPayloadType = 100,
            ssrc = 62812,
            aesKey = Bytes.fromHex("99887766554433221100112233445566"),
            aesIvMask = Bytes.fromHex("ffeeddccbbaa99887766554433221100"),
            rtpTimebase = 90_000,
            receiverRtcpEventLog = true,
            receiverRtcpDscp = 34,
        ),
        resolutions = listOf(Resolution(1920, 1080)),
        maxBitRate = 5_000_000,
    )
    return Offer(listOf(audio, h264, vp8), OfferProfile.OPENSCREEN_CHROME)
}

/** Recursively compares two JSON values, reporting the path of the first mismatch. */
fun jsonDiff(path: String, expected: Json, actual: Json?): String? {
    if (actual == null) return "$path: missing"
    return when (expected) {
        is Json.Obj -> {
            val a = actual.asObjOrNull() ?: return "$path: not an object"
            for ((k, v) in expected.entries) {
                jsonDiff(if (path.isEmpty()) k else "$path.$k", v, a[k])?.let { return it }
            }
            val extra = a.entries.keys - expected.entries.keys
            if (extra.isNotEmpty()) "$path: unexpected keys $extra" else null
        }
        is Json.Arr -> {
            val a = actual.asArrOrNull() ?: return "$path: not an array"
            if (a.size != expected.size) return "$path: length ${a.size} != ${expected.size}"
            for (i in 0 until expected.size) {
                jsonDiff("$path[$i]", expected[i], a[i])?.let { return it }
            }
            null
        }
        is Json.Num -> {
            val e = expected.asDoubleOrNull()
            val g = actual.asDoubleOrNull()
            if (e != null && g != null && e == g) null else "$path: number ${actual.encode()} != ${expected.encode()}"
        }
        else -> if (expected == actual) null else "$path: ${actual.encode()} != ${expected.encode()}"
    }
}

/** OFFER serialization and ANSWER parsing. */
fun runOfferAnswerTests() {
    T.section("OFFER serialization")

    val expected = Json.parse(SPEC_OFFER).asObjOrNull()!!
    val actual = buildSpecOffer().toMessage(seqNum = 1)
    val diff = jsonDiff("", expected, actual)
    T.check("generated OFFER matches the spec example key by key", diff == null, diff ?: "")

    T.assertEquals("aesKey is 32 hex digits", 32, Bytes.toHex(Bytes.fromHex("51027e4e2347cbcb49d57ef10177aeb9")).length)
    T.assertEquals(
        "timeBase for video",
        "1/90000",
        actual["offer"]?.asObjOrNull()?.get("supportedStreams")?.asArrOrNull()?.get(1)
            ?.asObjOrNull()?.get("timeBase")?.asStringOrNull(),
    )
    T.assertEquals(
        "rtpProfile is always cast",
        "cast",
        actual["offer"]?.asObjOrNull()?.get("supportedStreams")?.asArrOrNull()?.get(0)
            ?.asObjOrNull()?.get("rtpProfile")?.asStringOrNull(),
    )

    T.section("OFFER validation")

    T.assertThrows("indexes must start at 0 and increase by 1", "stream indexes") {
        Offer(listOf(buildSpecOffer().streams[1]), OfferProfile.OPENSCREEN_CHROME)
    }
    T.assertThrows("aesKey must be 16 bytes", "aesKey") {
        StreamCommon(
            index = 0, type = StreamType.VIDEO_SOURCE, codecName = "vp8", rtpPayloadType = 100,
            ssrc = 60000, aesKey = ByteArray(15), aesIvMask = ByteArray(16), rtpTimebase = 90_000,
        )
    }
    T.assertThrows("codecParameter prefix is validated", "codecParameter") {
        StreamCommon(
            index = 0, type = StreamType.VIDEO_SOURCE, codecName = "h264", codecParameter = "vp08.00",
            rtpPayloadType = 101, ssrc = 60000, aesKey = ByteArray(16), aesIvMask = ByteArray(16),
            rtpTimebase = 90_000,
        )
    }
    T.check(
        "an empty codecParameter is always valid",
        StreamCommon.codecParameterIsValid("h264", "") && StreamCommon.codecParameterIsValid("vp8", ""),
    )
    T.assertThrows("receiverRtcpDscp is bounds-checked", "receiverRtcpDscp") {
        StreamCommon(
            index = 0, type = StreamType.VIDEO_SOURCE, codecName = "vp8", rtpPayloadType = 100,
            ssrc = 60000, aesKey = ByteArray(16), aesIvMask = ByteArray(16), rtpTimebase = 90_000,
            receiverRtcpDscp = 64,
        )
    }
    T.assertThrows("mixed DSCP values are rejected", "mixed DSCP") {
        val a = buildSpecOffer().streams[0]
        val b = (buildSpecOffer().streams[1] as VideoOfferStream)
        val c = b.copy(common = b.common.copy(receiverRtcpDscp = 46))
        Offer(listOf(a, c), OfferProfile.OPENSCREEN_CHROME)
    }

    T.section("OfferBuilder")

    val videoOnly = OfferBuilder.build(
        profile = OfferProfile.OPENSCREEN_CHROME,
        videoCodecs = listOf("h264", "vp8"),
        audioCodecs = emptyList(),
    )
    T.assertEquals("video-only offer has two streams", 2, videoOnly.streams.size)
    T.assertEquals("first index is 0", 0, videoOnly.streams[0].common.index)
    T.assertEquals("second index is 1", 1, videoOnly.streams[1].common.index)
    T.assertEquals("h264 gets payload type 101", 101, videoOnly.streams[0].common.rtpPayloadType)
    T.assertEquals("vp8 gets payload type 100", 100, videoOnly.streams[1].common.rtpPayloadType)
    T.check(
        "video SSRCs are in the normal-priority range",
        videoOnly.streams.all { it.common.ssrc.toInt() in StreamingConstants.VIDEO_SSRC_RANGE },
    )
    T.check(
        "each stream gets its own key",
        !videoOnly.streams[0].common.aesKey.contentEquals(videoOnly.streams[1].common.aesKey),
    )
    T.check(
        "key and IV mask differ",
        !videoOnly.streams[0].common.aesKey.contentEquals(videoOnly.streams[0].common.aesIvMask),
    )

    val withAudio = OfferBuilder.build(
        profile = OfferProfile.OPENSCREEN_CHROME,
        videoCodecs = listOf("vp8"),
        audioCodecs = listOf("opus"),
    )
    T.assertEquals("audio streams come first", StreamType.AUDIO_SOURCE, withAudio.streams[0].common.type)
    T.check(
        "audio SSRC is in the higher-priority range",
        withAudio.streams[0].common.ssrc.toInt() in StreamingConstants.AUDIO_SSRC_RANGE,
    )
    T.assertEquals("opus timebase is 48000", 48_000, withAudio.streams[0].common.rtpTimebase)

    val hacked = OfferBuilder.build(
        profile = OfferProfile.OPENSCREEN_CHROME,
        videoCodecs = listOf("h264"),
        audioCodecs = listOf("opus"),
        androidTvPayloadTypeHack = true,
    )
    T.assertEquals("android-tv hack audio PT", RtpPayloadTypes.AUDIO_HACK_FOR_ANDROID_TV, hacked.streams[0].common.rtpPayloadType)
    T.assertEquals("android-tv hack video PT", RtpPayloadTypes.VIDEO_HACK_FOR_ANDROID_TV, hacked.streams[1].common.rtpPayloadType)

    T.check(
        "receiverRtcpDscp is omitted when null",
        !(videoOnly.streams[0].toJson(OfferProfile.OPENSCREEN_CHROME).has("receiverRtcpDscp")),
    )
    T.check(
        "rtpExtensions is omitted when empty",
        !(videoOnly.streams[0].toJson(OfferProfile.OPENSCREEN_CHROME).has("rtpExtensions")),
    )

    T.section("ANSWER parsing")

    val ok = AnswerParser.parse(Json.parse(SPEC_ANSWER).asObjOrNull()!!)
    T.check("spec ANSWER parses", ok is AnswerResult.Ok, (ok as? AnswerResult.Invalid)?.reason ?: "")
    val a = (ok as AnswerResult.Ok).answer
    T.assertEquals("udpPort", 33533, a.udpPort)
    T.assertEquals("sendIndexes", listOf(0, 1), a.sendIndexes)
    T.assertEquals("ssrcs", listOf(19089L, 62812L), a.ssrcs)
    T.assertEquals("receiver SSRC for index 1", 62812L, a.receiverSsrcForIndex(1))
    T.check("receiver SSRC for an unselected index is null", a.receiverSsrcForIndex(2) == null)
    T.check("DSCP is enabled when the sets match", a.dscpEnabled())
    T.assertEquals("audio maxSampleRate", 48000, a.constraints?.audio?.maxSampleRate)
    T.assertEquals("video maxBitRate", 10_000_000, a.constraints?.video?.maxBitRate)
    T.assertEquals("maxPixelsPerSecond", 6.2208e7, a.constraints?.video?.maxPixelsPerSecond)
    T.assertEquals("minResolution", Resolution(320, 180), a.constraints?.video?.minResolution)
    T.assertEquals("display scaling", DisplayDescription.SCALING_RECEIVER, a.display?.scaling)
    T.assertEquals("display frame rate", 60.0, a.display?.dimensions?.frameRateValue())
    T.assertEquals("rational frame rate", 30000.0 / 1001.0, app.castmirror.core.cast.streaming.messages.Dimensions(1, 1, "30000/1001").frameRateValue())

    // Absent and empty must be treated identically: libcast omits these keys when empty because
    // some senders mishandle empty arrays.
    val minimal = AnswerParser.parse(
        Json.parse("""{"type":"ANSWER","seqNum":7,"result":"ok","answer":{"udpPort":36916,"sendIndexes":[1],"ssrcs":[62812]}}""")
            .asObjOrNull()!!
    )
    T.check("minimal ANSWER parses", minimal is AnswerResult.Ok)
    val m = (minimal as AnswerResult.Ok).answer
    T.assertEquals("absent receiverRtcpEventLog reads as empty", emptyList<Int>(), m.receiverRtcpEventLog)
    T.assertEquals("absent rtpExtensions reads as empty", emptyList<List<String>>(), m.rtpExtensions)
    T.check("absent constraints is null", m.constraints == null)
    T.check("DSCP is off without the key", !m.dscpEnabled())

    val emptyArrays = AnswerParser.parse(
        Json.parse(
            """{"type":"ANSWER","seqNum":7,"result":"ok","answer":{"udpPort":1,"sendIndexes":[0],""" +
                """"ssrcs":[2],"receiverRtcpEventLog":[],"receiverRtcpDscp":[],"rtpExtensions":[]}}"""
        ).asObjOrNull()!!
    )
    T.check("empty arrays parse identically to absent", emptyArrays is AnswerResult.Ok)
    T.assertEquals(
        "empty arrays read as empty",
        emptyList<Int>(),
        (emptyArrays as AnswerResult.Ok).answer.receiverRtcpDscp,
    )

    T.section("ANSWER errors and malformed input")

    val err = AnswerParser.parse(
        Json.parse("""{"type":"ANSWER","seqNum":1,"result":"error","error":{"code":42,"description":"Invalid offer: unsupported codec"}}""")
            .asObjOrNull()!!
    )
    T.check("error ANSWER is recognised", err is AnswerResult.Error)
    T.assertEquals("error code", 42, (err as AnswerResult.Error).error.code)
    T.assertEquals("error description", "Invalid offer: unsupported codec", err.error.description)

    fun invalid(name: String, json: String, expectReason: String) {
        val r = AnswerParser.parse(Json.parse(json).asObjOrNull()!!)
        T.check(
            name,
            r is AnswerResult.Invalid && r.reason.contains(expectReason),
            "got $r",
        )
    }
    invalid("wrong type", """{"type":"OFFER","seqNum":1,"answer":{}}""", "not an ANSWER")
    invalid("negative seqNum", """{"type":"ANSWER","seqNum":-1,"answer":{}}""", "seqNum")
    invalid("missing seqNum", """{"type":"ANSWER","answer":{}}""", "seqNum")
    invalid("missing answer body", """{"type":"ANSWER","seqNum":1,"result":"ok"}""", "no answer body")
    invalid(
        "missing udpPort",
        """{"type":"ANSWER","seqNum":1,"answer":{"sendIndexes":[0],"ssrcs":[1]}}""",
        "udpPort",
    )
    invalid(
        "udpPort out of range",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":70000,"sendIndexes":[0],"ssrcs":[1]}}""",
        "out of range",
    )
    invalid(
        "udpPort zero",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":0,"sendIndexes":[0],"ssrcs":[1]}}""",
        "out of range",
    )
    invalid(
        "missing ssrcs",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":1,"sendIndexes":[0]}}""",
        "ssrcs",
    )
    invalid(
        "ssrcs and sendIndexes differ in length",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":1,"sendIndexes":[0,1],"ssrcs":[2]}}""",
        "differ in length",
    )
    invalid(
        "empty sendIndexes is a hard failure",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":1,"sendIndexes":[],"ssrcs":[]}}""",
        "no stream selected",
    )
    invalid(
        "negative sendIndexes entry",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":1,"sendIndexes":[-1],"ssrcs":[2]}}""",
        "negative",
    )
    invalid(
        "non-integer ssrc",
        """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":1,"sendIndexes":[0],"ssrcs":["x"]}}""",
        "non-integer",
    )

    // A present-but-invalid constraints object is logged and dropped rather than taking the whole
    // ANSWER down; the defaults are perfectly usable.
    val badConstraints = AnswerParser.parse(
        Json.parse(
            """{"type":"ANSWER","seqNum":1,"answer":{"udpPort":1,"sendIndexes":[0],"ssrcs":[2],""" +
                """"constraints":{"audio":{"maxSampleRate":0,"maxChannels":2,"maxBitRate":1},"video":{}}}}"""
        ).asObjOrNull()!!
    )
    T.check("invalid constraints do not invalidate the ANSWER", badConstraints is AnswerResult.Ok)
    T.check("invalid constraints are dropped", (badConstraints as AnswerResult.Ok).answer.constraints == null)
}
