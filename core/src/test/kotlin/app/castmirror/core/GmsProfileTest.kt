package app.castmirror.core

import app.castmirror.core.cast.streaming.messages.Offer
import app.castmirror.core.cast.streaming.messages.OfferBuilder
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.messages.Resolution
import app.castmirror.core.cast.streaming.messages.RtpExtensions
import app.castmirror.core.cast.streaming.messages.StreamType
import app.castmirror.core.cast.streaming.messages.VideoOfferStream
import app.castmirror.core.cast.streaming.rtp.RtpPayloadTypes
import app.castmirror.core.util.Bytes
import app.castmirror.core.util.Json

/**
 * A fixed reference OFFER matching what Google's Android "Cast screen" sender sends, used to hold
 * the generated GMS-dialect OFFER byte-stable. `<KEY>` and `<IV>` are substituted with the fixed
 * test vectors below.
 *
 * This is the contract: if the generated OFFER stops matching this key for key, a real Chromecast
 * will see something Google's own sender never sends.
 */
private const val GMS_OFFER_TEMPLATE = """
{
  "type": "OFFER",
  "seqNum": 1,
  "offer": {
    "udpPort": 2346,
    "supportedStreams": [
      {
        "index": 0,
        "type": "audio_source",
        "codecName": "aac",
        "ssrc": 1,
        "rtpPayloadType": 127,
        "rtpProfile": "cast",
        "rtpExtensions": ["adaptive_playout_delay"],
        "receiverRtcpEventLog": true,
        "targetDelay": 100,
        "storeTime": 100,
        "timeBase": "1/48000",
        "errorRecoveryMode": "castv2",
        "channels": 2,
        "bitRate": 128000,
        "sampleRate": 48000,
        "aesIvMask": "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
        "aesKey": "a1b2c3d4e5f60718293a4b5c6d7e8f90"
      },
      {
        "index": 1,
        "type": "video_source",
        "codecName": "h264",
        "ssrc": 11,
        "rtpPayloadType": 96,
        "rtpProfile": "cast",
        "rtpExtensions": ["adaptive_playout_delay"],
        "receiverRtcpEventLog": true,
        "targetDelay": 100,
        "storeTime": 100,
        "timeBase": "1/90000",
        "errorRecoveryMode": "castv2",
        "profile": "main",
        "level": "4",
        "maxBitRate": 6000000,
        "maxFrameRate": "60000/1001",
        "resolutions": [{"height": 720, "width": 1280}],
        "intraRefreshEnable": false,
        "aesIvMask": "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
        "aesKey": "a1b2c3d4e5f60718293a4b5c6d7e8f90"
      },
      {
        "index": 2,
        "type": "video_source",
        "codecName": "vp9",
        "ssrc": 12,
        "rtpPayloadType": 103,
        "rtpProfile": "cast",
        "rtpExtensions": ["adaptive_playout_delay"],
        "receiverRtcpEventLog": true,
        "targetDelay": 100,
        "storeTime": 100,
        "timeBase": "1/90000",
        "errorRecoveryMode": "castv2",
        "profile": "main",
        "level": "4",
        "maxBitRate": 6000000,
        "maxFrameRate": "60000/1001",
        "resolutions": [{"height": 720, "width": 1280}],
        "intraRefreshEnable": false,
        "aesIvMask": "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
        "aesKey": "a1b2c3d4e5f60718293a4b5c6d7e8f90"
      }
    ],
    "castMode": "mirroring"
  }
}
"""

private val TEST_KEY = Bytes.fromHex("a1b2c3d4e5f60718293a4b5c6d7e8f90")
private val TEST_IV = Bytes.fromHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0")

/** The GMS OFFER dialect, checked against the fixed reference OFFER. */
fun runGmsProfileTests() {
    T.section("GMS profile defaults")

    val gms = OfferProfile.GMS_SYSTEM_MIRRORING
    T.assertEquals("the default profile is GMS", gms, OfferProfile.DEFAULT)
    T.assertEquals("A/V app id", "674A0243", gms.audioVideoAppId)
    T.assertEquals("audio-only app id", "8E6C866D", gms.audioOnlyAppId)
    T.assertEquals("target delay is 100 ms, not 400", 100, gms.targetDelayMs)
    T.assertEquals("offer.udpPort is the constant 2346", 2346, gms.offerUdpPort)
    T.assertEquals("error recovery mode is stated explicitly", "castv2", gms.errorRecoveryMode)
    T.assertEquals("max bitrate", 6_000_000, gms.maxBitRate)
    T.assertEquals("max frame rate", "60000/1001", gms.maxFrameRate)
    T.assertEquals("default resolution", listOf(Resolution(1280, 720)), gms.resolutions)
    T.assertEquals("audio codec is aac, not opus", "aac", gms.audioCodec)
    T.assertEquals("rtp extension token", listOf(RtpExtensions.ADAPTIVE_PLAYOUT_DELAY), gms.rtpExtensions)
    T.assertEquals("seqNum starts at 1", 1, gms.initialSeqNum)
    T.check("android-tv payload types are unconditional", gms.androidTvPayloadTypes)
    T.check("one key pair is shared across streams", gms.sharedKeys)
    T.check("SSRCs are the fixed constants", gms.fixedSsrcs)
    T.check("LAUNCH omits language", gms.launchLanguage == null)
    T.check("LAUNCH omits supportedAppTypes", gms.launchSupportedAppTypes == null)

    val os = OfferProfile.OPENSCREEN_CHROME
    T.assertEquals("openscreen A/V app id", "0F5096E8", os.audioVideoAppId)
    T.assertEquals("openscreen target delay", 400, os.targetDelayMs)
    T.check("openscreen does not force the android-tv payload types", !os.androidTvPayloadTypes)
    T.check("openscreen emits codecParameter", os.emitCodecParameter)
    T.check("openscreen does not emit storeTime", !os.emitStoreTime)

    T.section("GMS OFFER matches the reference OFFER")

    val offer = OfferBuilder.build(
        profile = OfferProfile.GMS_SYSTEM_MIRRORING,
        videoCodecs = listOf("h264", "vp9"),
        audioCodecs = listOf("aac"),
        aesKey = TEST_KEY,
        aesIvMask = TEST_IV,
    )
    val expected = Json.parse(GMS_OFFER_TEMPLATE).asObjOrNull()!!
    val actual = offer.toMessage(seqNum = 1)
    val diff = jsonDiff("", expected, actual)
    T.check("generated GMS OFFER matches the template key by key", diff == null, diff ?: "")

    T.section("GMS OFFER details")

    T.assertEquals("audio SSRC is 1", 1L, offer.streams[0].common.ssrc)
    T.assertEquals("first video SSRC is 11", 11L, offer.streams[1].common.ssrc)
    T.assertEquals("second video SSRC is 12", 12L, offer.streams[2].common.ssrc)
    T.assertEquals("audio payload type is 127", 127, offer.streams[0].common.rtpPayloadType)
    T.assertEquals("first video payload type is 96", 96, offer.streams[1].common.rtpPayloadType)
    T.assertEquals("second video payload type is 103", 103, offer.streams[2].common.rtpPayloadType)
    T.check(
        "every stream shares one key",
        offer.streams.all { it.common.aesKey.contentEquals(TEST_KEY) },
    )
    T.check(
        "every stream shares one IV mask",
        offer.streams.all { it.common.aesIvMask.contentEquals(TEST_IV) },
    )
    // GMS sends 96 for the first video stream whatever the codec, which is precisely openscreen's
    // `use_android_rtp_hack`. The canonical mapping would have given 101 for H.264.
    T.assertEquals("canonical h264 PT would have been 101", 101, RtpPayloadTypes.forCodec("h264"))
    T.assertEquals(
        "openscreen maps vp9 to VP8's payload type",
        RtpPayloadTypes.VIDEO_VP8,
        RtpPayloadTypes.forCodec("vp9"),
    )
    T.assertEquals("GMS maps vp9 to 103", 103, RtpPayloadTypes.forCodecGms("vp9"))

    T.section("GMS OFFER omits the keys GMS omits")

    val streamJson = offer.streams[1].toJson(OfferProfile.GMS_SYSTEM_MIRRORING)
    for (absent in listOf("codecParameter", "receiverRtcpDscp", "protection", "channels", "sampleRate")) {
        T.check("video stream omits $absent", !streamJson.has(absent))
    }
    val audioJson = offer.streams[0].toJson(OfferProfile.GMS_SYSTEM_MIRRORING)
    for (absent in listOf("codecParameter", "receiverRtcpDscp", "protection", "maxBitRate", "resolutions")) {
        T.check("audio stream omits $absent", !audioJson.has(absent))
    }
    T.check("the offer body carries no constraints", !offer.toJson().has("constraints"))

    T.section("GMS OFFER with audio omitted")

    // System audio is not capturable on GrapheneOS the way GMS captures it, so video-only is the
    // realistic default. Everything else must stay identical; only the index-0 stream disappears.
    val videoOnly = OfferBuilder.build(
        profile = OfferProfile.GMS_SYSTEM_MIRRORING,
        videoCodecs = listOf("h264", "vp9"),
        audioCodecs = emptyList(),
        aesKey = TEST_KEY,
        aesIvMask = TEST_IV,
    )
    T.assertEquals("two streams remain", 2, videoOnly.streams.size)
    T.assertEquals("h264 moves to index 0", 0, videoOnly.streams[0].common.index)
    T.assertEquals("vp9 moves to index 1", 1, videoOnly.streams[1].common.index)
    T.check("no audio stream remains", videoOnly.streams.none { it.common.type == StreamType.AUDIO_SOURCE })
    T.assertEquals("h264 keeps SSRC 11", 11L, videoOnly.streams[0].common.ssrc)
    T.assertEquals("h264 keeps payload type 96", 96, videoOnly.streams[0].common.rtpPayloadType)
    T.assertEquals("vp9 keeps SSRC 12", 12L, videoOnly.streams[1].common.ssrc)
    T.assertEquals("vp9 keeps payload type 103", 103, videoOnly.streams[1].common.rtpPayloadType)

    // Every key of the video stream must be byte-identical to the audio+video case apart from
    // the index, which is what "keep everything else identical" means.
    val withAudioVideoJson = offer.streams[1].toJson(OfferProfile.GMS_SYSTEM_MIRRORING)
    val videoOnlyJson = videoOnly.streams[0].toJson(OfferProfile.GMS_SYSTEM_MIRRORING)
    T.assertEquals(
        "the video stream's key set is unchanged",
        withAudioVideoJson.entries.keys.toList(),
        videoOnlyJson.entries.keys.toList(),
    )
    val differing = withAudioVideoJson.entries.filter { (k, v) -> videoOnlyJson[k] != v }.keys
    T.assertEquals("only the index differs", setOf("index"), differing)

    T.section("GMS OFFER: sendIndexes handling without audio")

    // The ANSWER refers to the indexes we actually emitted, so a video-only offer must resolve
    // sendIndex 0 to the video stream rather than to a phantom audio stream.
    T.assertEquals(
        "index 0 is the video stream when audio is absent",
        "h264",
        videoOnly.streamAtIndex(0)?.common?.codecName,
    )
    T.assertEquals(
        "index 0 is the audio stream when audio is present",
        "aac",
        offer.streamAtIndex(0)?.common?.codecName,
    )
    T.check("an index beyond the offer resolves to null", videoOnly.streamAtIndex(2) == null)
    T.assertEquals(
        "firstVideoStream skips audio",
        "h264",
        (offer.firstVideoStream() as VideoOfferStream).common.codecName,
    )

    T.section("GMS resolution override")

    val custom = OfferBuilder.build(
        profile = OfferProfile.GMS_SYSTEM_MIRRORING,
        videoCodecs = listOf("h264"),
        resolutions = listOf(Resolution(1920, 1080)),
        maxBitRate = 8_000_000,
        aesKey = TEST_KEY,
        aesIvMask = TEST_IV,
    )
    val customJson = custom.streams[0].toJson(OfferProfile.GMS_SYSTEM_MIRRORING)
    T.assertEquals(
        "resolutions are written height-first",
        listOf("height", "width"),
        customJson["resolutions"]!!.asArrOrNull()!![0].asObjOrNull()!!.entries.keys.toList(),
    )
    T.assertEquals(
        "the resolution override is honoured",
        1920,
        customJson["resolutions"]!!.asArrOrNull()!![0].asObjOrNull()!!["width"]?.asIntOrNull(),
    )
    T.assertEquals("the bitrate override is honoured", 8_000_000, customJson["maxBitRate"]?.asIntOrNull())

    T.section("GMS launch ladder")

    val ladder = SessionConfig.DEFAULT_LAUNCH_LADDER
    T.assertEquals("two rungs", 2, ladder.size)
    T.assertEquals("rung 1 is GMS's app id", "674A0243", ladder[0].appId)
    T.assertEquals("rung 1 uses the GMS dialect", OfferProfile.GMS_SYSTEM_MIRRORING, ladder[0].profile)
    T.assertEquals("rung 2 is the Chrome app id", "0F5096E8", ladder[1].appId)
    T.assertEquals("rung 2 uses the openscreen dialect", OfferProfile.OPENSCREEN_CHROME, ladder[1].profile)
    T.assertEquals(
        "an audio-only device gets the audio-only ladder",
        listOf("8E6C866D", "85CDB22F"),
        SessionConfig.ladderFor(hasVideoOutput = false).map { it.appId },
    )
    T.assertEquals(
        "a device with video output gets the A/V ladder",
        listOf("674A0243", "0F5096E8"),
        SessionConfig.ladderFor(hasVideoOutput = true).map { it.appId },
    )

    T.section("GMS message vocabulary")

    // OFFER, ANSWER and STATISTICS are the entire vocabulary GMS's mirroring v2 knows.
    T.assertEquals("OFFER", "OFFER", app.castmirror.core.cast.streaming.messages.StreamingMessageTypes.OFFER)
    T.assertEquals("ANSWER", "ANSWER", app.castmirror.core.cast.streaming.messages.StreamingMessageTypes.ANSWER)
    T.assertEquals(
        "STATISTICS",
        "STATISTICS",
        app.castmirror.core.cast.streaming.messages.StreamingMessageTypes.STATISTICS,
    )

    T.section("Offer/Answer envelope conventions")

    T.assertEquals("the offer body key order is GMS's", listOf("udpPort", "supportedStreams", "castMode"), offer.toJson().entries.keys.toList())
    val envelope = offer.toMessage(7)
    T.assertEquals("envelope keys", listOf("type", "seqNum", "offer"), envelope.entries.keys.toList())
    T.assertEquals("envelope type", "OFFER", envelope["type"]?.asStringOrNull())
    T.assertEquals("envelope seqNum", 7, envelope["seqNum"]?.asIntOrNull())

    T.section("ANSWER: ipaddr and display down-scaling")

    val answered = app.castmirror.core.cast.streaming.messages.AnswerParser.parse(
        Json.parse(
            """{"type":"ANSWER","seqNum":1,"result":"ok","answer":{"udpPort":36916,""" +
                """"sendIndexes":[0],"ssrcs":[12],"ipaddr":"10.0.0.7",""" +
                """"display":{"dimensions":{"width":854,"height":480,"frameRate":"30"},"scaling":"receiver"}}}"""
        ).asObjOrNull()!!
    )
    T.check("ANSWER with ipaddr parses", answered is app.castmirror.core.cast.streaming.messages.AnswerResult.Ok)
    val a = (answered as app.castmirror.core.cast.streaming.messages.AnswerResult.Ok).answer
    T.assertEquals("ipaddr is captured", "10.0.0.7", a.ipaddr)
    T.assertEquals(
        "a smaller display in both axes replaces the offered resolution",
        Resolution(854, 480),
        a.effectiveResolution(Resolution(1280, 720)),
    )
    // The 854x480 display is wider but shorter than an offered 800x720, so the shrink rule --
    // which requires BOTH axes to be smaller -- must leave the offered resolution alone.
    T.assertEquals(
        "a display smaller in only one axis is ignored",
        Resolution(800, 720),
        a.effectiveResolution(Resolution(800, 720)),
    )
    T.assertEquals(
        "an absent display leaves the resolution alone",
        Resolution(1280, 720),
        Answerless.effectiveResolution(Resolution(1280, 720)),
    )
}

/** An ANSWER with no `display`, for the "leaves the resolution alone" case. */
private val Answerless = app.castmirror.core.cast.streaming.messages.Answer(
    udpPort = 1234,
    sendIndexes = listOf(0),
    ssrcs = listOf(2L),
)
