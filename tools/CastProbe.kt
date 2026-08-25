package app.castmirror.tools

import app.castmirror.core.CastAppIds
import app.castmirror.core.cast.channel.CastChannel
import app.castmirror.core.cast.channel.CastChannelListener
import app.castmirror.core.cast.channel.ConnectParams
import app.castmirror.core.cast.channel.CastMessage
import app.castmirror.core.cast.channel.CastProtocolException
import app.castmirror.core.cast.channel.Namespaces
import app.castmirror.core.cast.channel.TlsCastSocket
import app.castmirror.core.cast.streaming.messages.AnswerResult
import app.castmirror.core.cast.streaming.messages.OfferBuilder
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.messages.SessionMessenger
import app.castmirror.core.discovery.MdnsDiscovery
import app.castmirror.core.util.Bytes
import app.castmirror.core.util.Json
import kotlin.system.exitProcess

private const val USAGE = """
castprobe -- a one-shot Chromecast protocol probe

  java -cp castprobe.jar app.castmirror.tools.CastProbeKt discover [seconds]
  java -cp castprobe.jar app.castmirror.tools.CastProbeKt <host> [appId] [options]

Runs the full control-channel bring-up against a real receiver on your LAN and prints
everything it sees, then exits without sending any media:

  TLS connect -> CONNECT -> GET_STATUS -> GET_APP_AVAILABILITY -> LAUNCH
              -> CONNECT to the app's transportId -> OFFER -> ANSWER

Arguments:
  <host>            receiver IP address (or hostname)
  [appId]           receiver app id to launch (default: the profile's -- 674A0243 for gms)

Options:
  --raw             CAPTURE MODE. Print every inbound frame's exact bytes before decode: a full
                    hex dump (capped at 4 KB/frame), a tolerant protobuf field walk, and the
                    decoded CastMessage summary. Sends GET_STATUS and then holds the channel open
                    ~8 s to catch RECEIVER_STATUS regardless of whether it correlates. Use this to
                    diagnose "every request times out" -- it shows how the receiver's bytes differ
                    from what the decoder expects. Does not LAUNCH or OFFER.
  --profile=gms|openscreen
                    which OFFER dialect to send (default gms, matching the core default).
                    gms        = what Google's "Cast screen" sends: app 674A0243, aac/h264/vp9,
                                 payload types 127/96, targetDelay 100, storeTime, udpPort 2346,
                                 rtpExtensions ["adaptive_playout_delay"], profile/level main/4.
                    openscreen = what Chrome sends: app 0F5096E8, opus/h264/vp8, payload types
                                 96/101/100, targetDelay 400, codecParameter, no extras.
  --port=N          Cast control port (default 8009; always prefer the mDNS SRV value)
  --android-hack    force the Android-TV RTP payload types (audio 127, video 96). Implied by
                    --profile=gms, which always uses them.
  --audio           also offer an audio stream (aac for gms, opus for openscreen).
                    Default is video only -- system audio needs privileged permissions.
  --vp9             also offer a second video stream, as GMS does
  --legacy-tls      enable old ciphers/TLSv1 for 2013-era receivers
  --keep=N          stay connected N seconds after the ANSWER (default 2)
  --no-stop         leave the receiver app running on exit

Examples:
  java -cp castprobe.jar app.castmirror.tools.CastProbeKt discover
  java -cp castprobe.jar app.castmirror.tools.CastProbeKt 192.168.1.50
  java -cp castprobe.jar app.castmirror.tools.CastProbeKt 192.168.1.50 --profile=openscreen
  java -cp castprobe.jar app.castmirror.tools.CastProbeKt 192.168.1.50 --audio --vp9
"""

private fun log(message: String) {
    println("[${String.format("%8.3f", (System.nanoTime() - startNanos) / 1e9)}] $message")
}

private val startNanos = System.nanoTime()

/** Pretty-prints JSON with two-space indentation, for eyeballing raw receiver replies. */
private fun pretty(value: Json, indent: String = ""): String = when (value) {
    is Json.Obj -> if (value.entries.isEmpty()) "{}" else buildString {
        append("{\n")
        val inner = "$indent  "
        value.entries.entries.forEachIndexed { i, (k, v) ->
            append(inner).append('"').append(k).append("\": ").append(pretty(v, inner))
            if (i < value.entries.size - 1) append(',')
            append('\n')
        }
        append(indent).append('}')
    }
    is Json.Arr -> if (value.size == 0) "[]" else buildString {
        append("[\n")
        val inner = "$indent  "
        value.items.forEachIndexed { i, v ->
            append(inner).append(pretty(v, inner))
            if (i < value.size - 1) append(',')
            append('\n')
        }
        append(indent).append(']')
    }
    else -> value.encode()
}

private fun prettyOrRaw(payload: String?): String =
    payload?.let { Json.parseOrNull(it)?.let(::pretty) ?: it } ?: "<binary>"

/** Cap on the per-frame hex dump, in bytes. Above this the dump is truncated with a note. */
private const val RAW_HEX_CAP = 4096

/** Cap on a decoded-string preview inside the field walk, in characters. */
private const val WALK_UTF8_CAP = 240

private fun hex(bytes: ByteArray, from: Int, to: Int): String {
    val sb = StringBuilder((to - from) * 2)
    val digits = "0123456789abcdef"
    for (i in from until to) {
        val v = bytes[i].toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0xF])
    }
    return sb.toString()
}

/** True when every char is printable or ordinary whitespace, so it is safe to echo on one line. */
private fun isPrintable(s: String): Boolean =
    s.all { it == '\n' || it == '\r' || it == '\t' || (it.code in 0x20..0x10FFFF && !it.isISOControl()) }

/**
 * Decodes a length-delimited field's bytes as UTF-8, returning the string only when it round-trips
 * and is printable — otherwise `null`, so the walk never prints mojibake for genuinely binary data.
 */
private fun utf8OrNull(bytes: ByteArray, from: Int, to: Int): String? {
    val slice = bytes.copyOfRange(from, to)
    val decoded = String(slice, Charsets.UTF_8)
    if (!decoded.toByteArray(Charsets.UTF_8).contentEquals(slice)) return null
    if (!isPrintable(decoded)) return null
    return decoded
}

private class WalkError(val pos: Int, override val message: String) : RuntimeException(message)

/**
 * Prints a tolerant top-level protobuf field walk of [body].
 *
 * Uses the same varint logic as the real decoder (`ProtoReader`) but never throws on the caller:
 * unknown wire types 3/4 (group start/end, unused by Cast) are annotated rather than treated as
 * fatal, and any malformed point prints `WALK-ERROR at pos <p>: <reason>` and stops — that line is
 * the smoking gun when the receiver's framing disagrees with what the decoder assumes.
 */
private fun walkProtobuf(body: ByteArray) {
    var pos = 0
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= body.size) throw WalkError(pos, "truncated varint")
            val b = body[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw WalkError(pos, "varint longer than 64 bits")
        }
    }
    try {
        while (pos < body.size) {
            val tag = readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint()
                    println("  field=$field wire=0 varint=$v")
                }
                1 -> {
                    if (pos + 8 > body.size) throw WalkError(pos, "truncated 64-bit field")
                    println("  field=$field wire=1 fixed64=0x${hex(body, pos, pos + 8)}")
                    pos += 8
                }
                2 -> {
                    val lenStart = pos
                    val len = readVarint().toInt()
                    if (len < 0) throw WalkError(lenStart, "negative length $len")
                    if (pos + len > body.size) throw WalkError(pos, "length $len overruns the frame (only ${body.size - pos} left)")
                    val from = pos
                    val to = pos + len
                    pos = to
                    val utf8 = utf8OrNull(body, from, to)
                    if (utf8 != null) {
                        val shown = if (utf8.length > WALK_UTF8_CAP) utf8.take(WALK_UTF8_CAP) + "…(+${utf8.length - WALK_UTF8_CAP} chars)" else utf8
                        println("  field=$field wire=2 len=$len utf8=\"$shown\"")
                    } else {
                        println("  field=$field wire=2 len=$len (non-utf8 bytes)")
                    }
                }
                5 -> {
                    if (pos + 4 > body.size) throw WalkError(pos, "truncated 32-bit field")
                    println("  field=$field wire=5 fixed32=0x${hex(body, pos, pos + 4)}")
                    pos += 4
                }
                3 -> println("  field=$field wire=3 (group start; not modeled by Cast — annotating, continuing)")
                4 -> println("  field=$field wire=4 (group end; not modeled by Cast — annotating, continuing)")
                else -> throw WalkError(pos, "unknown wire type $wire")
            }
        }
    } catch (e: WalkError) {
        println("  WALK-ERROR at pos ${e.pos}: ${e.message}")
    }
}

/**
 * The `--raw` per-frame dump: the exact received bytes, a field walk, and the decoded summary.
 *
 * Installed as [CastChannel.onRawFrame], so it runs on the reader thread *before* decode and sees
 * the body verbatim.
 */
/** Set by [dumpRawFrame] the first time any frame is captured, so the summary can note silence. */
private val rawSawFrame = java.util.concurrent.atomic.AtomicBoolean(false)

private fun dumpRawFrame(body: ByteArray) {
    rawSawFrame.set(true)
    println()
    val cap = minOf(body.size, RAW_HEX_CAP)
    val note = if (body.size > RAW_HEX_CAP) "  (truncated to first $RAW_HEX_CAP of ${body.size} bytes)" else ""
    log("RAW ${body.size} bytes:$note")
    println(hex(body, 0, cap))
    walkProtobuf(body)
    val decoded = try {
        CastMessage.decode(body)
    } catch (e: Exception) {
        println("  decoded: DECODE THREW ${e::class.simpleName}: ${e.message}")
        return
    }
    val utf8 = decoded.payloadUtf8
    println(
        "  decoded ns=${decoded.namespace} src=${decoded.sourceId}->dst=${decoded.destinationId} " +
            "payloadType=${decoded.payloadType} utf8=${if (utf8 == null) "null" else "len ${utf8.length}"}"
    )
}

/**
 * `--raw` capture flow: provoke a RECEIVER_STATUS and hold the channel open to catch it verbatim.
 *
 * Deliberately does **not** run the LAUNCH/OFFER ladder — those just time out here and add 20+ s.
 * GET_STATUS alone provokes the RECEIVER_STATUS whose bytes are the target, and it is fired on a
 * background thread so its own 5 s reply timeout never delays or aborts the capture window. Every
 * inbound frame is dumped by [dumpRawFrame] via the raw hook regardless of correlation.
 */
private fun runRawCapture(channel: CastChannel, keepSeconds: Long) {
    val captureSeconds = maxOf(keepSeconds, 8)
    // The raw hook (::dumpRawFrame) was installed before start(), so early frames are already
    // being captured; it records rawSawFrame for the closing summary.

    log("RAW CAPTURE MODE: dumping every inbound frame's exact bytes for ${captureSeconds}s")
    log(">>> CONNECT sender-0 -> receiver-0")
    channel.openVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)

    // Fire GET_STATUS without letting its timeout gate the capture window.
    Thread {
        try {
            channel.getStatus()
        } catch (e: Exception) {
            log("(GET_STATUS did not correlate: ${e::class.simpleName}: ${e.message} — expected here; " +
                "the raw dump above is what matters)")
        }
    }.apply { isDaemon = true; start() }
    log(">>> GET_STATUS sent; waiting for inbound frames ...")

    try {
        Thread.sleep(captureSeconds * 1000)
    } catch (_: InterruptedException) {
    }

    println()
    if (!rawSawFrame.get()) {
        log("NO inbound frames were captured in ${captureSeconds}s.")
        log("That points at the transport, not the decoder: check the receiver is reachable, that")
        log("nothing is filtering the TLS stream, and that CONNECT was accepted.")
    } else {
        log("capture window elapsed. The RAW/field-walk blocks above are the receiver's exact bytes;")
        log("compare each top-level field number+wire type against what CastMessage.decode expects.")
    }
    channel.closeVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)
    channel.close()
    log("done.")
}

/**
 * The one-time device test: run this from a laptop on the same LAN as the Chromecast to confirm
 * the whole control path works before wiring anything up on Android.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") {
        println(USAGE.trim())
        exitProcess(if (args.isEmpty()) 2 else 0)
    }

    if (args[0] == "discover") {
        val seconds = args.getOrNull(1)?.toLongOrNull() ?: 5
        log("browsing _googlecast._tcp.local for ${seconds}s ...")
        val devices = MdnsDiscovery.discover(timeoutMs = seconds * 1000)
        if (devices.isEmpty()) {
            log("no devices found. mDNS is often blocked by AP client isolation, mesh routers")
            log("and VLANs -- try connecting by IP directly.")
            exitProcess(1)
        }
        for (d in devices) {
            println()
            println("  ${d.friendlyName}")
            println("    host          ${d.host}:${d.port}")
            println("    model (md)    ${d.model ?: "<absent>"}")
            println("    id            ${d.deviceId}")
            println("    ca            ${d.capabilities} (0x${d.capabilities.toString(16)})" +
                "  video=${d.hasVideoOutput()} audio=${d.hasAudioOutput()}")
            println("    ve/st         ${d.protocolVersion}/${d.status}${if (d.isBusy()) " (busy)" else ""}")
            d.statusText?.takeIf { it.isNotEmpty() }?.let { println("    rs            $it") }
            println("    all TXT       ${d.txt}")
        }
        println()
        log("probe one with: CastProbeKt ${devices[0].host}")
        exitProcess(0)
    }

    val host = args[0]
    val positional = args.drop(1).filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    val profileArg = flags.firstOrNull { it.startsWith("--profile") }
        ?.substringAfter('=', "")
        ?.lowercase()
        ?.ifEmpty { null }
    val profile = if (profileArg == null) OfferProfile.DEFAULT else OfferProfile.byName(profileArg) ?: run {
        log("unknown --profile '$profileArg'; expected gms or openscreen")
        exitProcess(2)
    }
    val appId = positional.firstOrNull() ?: profile.audioVideoAppId
    val port = flags.firstOrNull { it.startsWith("--port=") }?.removePrefix("--port=")?.toIntOrNull()
        ?: TlsCastSocket.DEFAULT_PORT
    val rawCapture = flags.contains("--raw")
    val androidHack = flags.contains("--android-hack")
    val withAudio = flags.contains("--audio")
    val withVp9 = flags.contains("--vp9")
    val legacyTls = flags.contains("--legacy-tls")
    val keepSeconds = flags.firstOrNull { it.startsWith("--keep=") }?.removePrefix("--keep=")?.toLongOrNull() ?: 2
    val sendStop = !flags.contains("--no-stop")

    log("profile=${profile.cliName}  appId=$appId")
    log("connecting to $host:$port (TLS, certificate validation disabled)")
    val socket = try {
        TlsCastSocket.connect(host, port, legacyCiphers = legacyTls)
    } catch (e: Exception) {
        log("FAILED to connect: ${e::class.simpleName}: ${e.message}")
        log("check that the device is reachable and that port $port is open.")
        if (!legacyTls) log("for a 1st-gen Chromecast, retry with --legacy-tls")
        exitProcess(1)
    }
    log("TLS handshake complete with ${socket.description}")

    // The CONNECT shape follows the profile too: GMS's `sdkType`/`origin.package`/
    // `protocolVersion: 3` payload, or the openscreen/Chrome one.
    val channel = CastChannel(
        socket,
        connectParams = if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) {
            ConnectParams.gms(appPackage = "app.castmirror.probe", senderVersion = "1")
        } else {
            ConnectParams.openscreen(userAgent = "castprobe/1.0")
        },
    )
    channel.addListener(object : CastChannelListener {
        override fun onMessage(message: CastMessage) {
            // Heartbeat traffic is noise; everything else is what we came to see.
            if (message.namespace == Namespaces.HEARTBEAT) return
            println()
            log("<<< ${message.namespace}  ${message.sourceId} -> ${message.destinationId}")
            println(prettyOrRaw(message.payloadUtf8))
        }

        override fun onClosed(cause: Throwable?) {
            log("channel closed${cause?.let { ": $it" } ?: ""}")
        }
    })

    // The raw pre-decode hook is installed before start(), so the very first inbound frame is
    // captured verbatim. It runs alongside the decoded onMessage printing above.
    if (rawCapture) channel.onRawFrame = ::dumpRawFrame

    channel.start()

    if (rawCapture) {
        runRawCapture(channel, keepSeconds)
        return
    }

    var sessionId: String? = null
    try {
        // Virtual connection 1: sender-0 -> receiver-0. No deviceauth: the receiver never
        // challenges the sender, so there is nothing a non-GMS sender needs from Google.
        log(">>> CONNECT sender-0 -> receiver-0")
        channel.openVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)

        log(">>> GET_STATUS")
        val status = channel.getStatus()
        log("receiver is running ${status.applications.size} application(s)")
        for (a in status.applications) {
            log("  appId=${a.appId} idle=${a.isIdleScreen} transportId=${a.transportId} \"${a.displayName}\"")
        }

        log(">>> GET_APP_AVAILABILITY ${CastAppIds.ALL_MIRRORING}")
        val availability = channel.getAppAvailability(CastAppIds.ALL_MIRRORING)
        for ((id, value) in availability) log("  $id -> $value")
        if (availability[appId] == "APP_UNAVAILABLE") {
            log("WARNING: $appId reports APP_UNAVAILABLE; launching anyway to see what happens")
        }

        log(">>> LAUNCH $appId")
        val launched = try {
            channel.launch(
                appId,
                language = profile.launchLanguage,
                supportedAppTypes = profile.launchSupportedAppTypes,
            )
        } catch (e: CastProtocolException) {
            log("LAUNCH FAILED: ${e.message}")
            log("reason=${e.reason}. NOT_FOUND means this device does not support that app id;")
            log("try one of ${CastAppIds.ALL_MIRRORING}")
            throw e
        }
        val app = launched.applicationFor(appId)
            ?: run {
                log("RECEIVER_STATUS did not name $appId. Aborting.")
                throw IllegalStateException("app not present in RECEIVER_STATUS")
            }
        sessionId = app.sessionId
        log("launched: sessionId=${app.sessionId} transportId=${app.transportId}")
        log("namespaces: ${app.namespaces}")
        if (Namespaces.WEBRTC !in app.namespaces) {
            log("WARNING: the app does not advertise ${Namespaces.WEBRTC}; the OFFER may be ignored")
        }

        // Virtual connection 2: a distinct per-session sender id -> the app's transportId.
        // Skipping this makes the receiver silently drop the OFFER. It is the single most common
        // bug in a mirroring sender.
        val senderId = CastChannel.uniqueSenderId()
        log(">>> CONNECT $senderId -> ${app.transportId}")
        channel.openVirtualConnection(app.transportId!!, senderId)

        val messenger = SessionMessenger(
            channel = channel,
            sourceId = senderId,
            destinationId = app.transportId,
            initialSeqNum = profile.initialSeqNum,
        )

        // GMS never sends this; it is informational only and a missing reply is harmless.
        log(">>> GET_CAPABILITIES (informational; never pick a mirroring codec from this)")
        val caps = messenger.getCapabilities()
        if (caps == null) log("  no CAPABILITIES_RESPONSE (means: no remoting support; harmless)")
        else log("  remoting=${caps.remoting} mediaCaps=${caps.mediaCaps}")

        val videoCodecs =
            if (withVp9) profile.videoCodecs + listOf(if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) "vp9" else "vp8")
            else profile.videoCodecs
        val offer = OfferBuilder.build(
            profile = profile,
            videoCodecs = videoCodecs.distinct(),
            audioCodecs = if (withAudio) listOf(profile.audioCodec) else emptyList(),
            androidTvPayloadTypeHack = androidHack,
        )
        println()
        log(">>> OFFER (${offer.streams.size} stream(s)${if (androidHack) ", Android-TV payload types" else ""})")
        println(pretty(offer.toMessage(messenger.peekSeqNum())))
        for (s in offer.streams) {
            log(
                "  index=${s.common.index} ${s.common.codecName} pt=${s.common.rtpPayloadType} " +
                    "ssrc=${s.common.ssrc} key=${Bytes.toHex(s.common.aesKey)}"
            )
        }

        val result = messenger.sendOffer(offer)
        println()
        when (result) {
            is AnswerResult.Ok -> {
                val a = result.answer
                log("ANSWER OK")
                val mediaHost = a.ipaddr ?: host
                log("  udpPort      ${a.udpPort}   <- send RTP/RTCP to $mediaHost:${a.udpPort}")
                a.ipaddr?.let { log("  ipaddr       $it   (overrides the control-channel address)") }
                log("  sendIndexes  ${a.sendIndexes}")
                log("  ssrcs        ${a.ssrcs}   (receiver SSRCs)")
                for ((i, index) in a.sendIndexes.withIndex()) {
                    val s = offer.streamAtIndex(index)
                    log("  accepted index $index -> ${s?.common?.codecName}, receiver ssrc ${a.ssrcs[i]}")
                }
                log("  eventLog     ${a.receiverRtcpEventLog}")
                log("  dscp         ${a.receiverRtcpDscp} (enabled=${a.dscpEnabled()})")
                a.constraints?.let {
                    log("  video max    ${it.video.maxDimensions} @ ${it.video.maxBitRate} bps")
                    log("  video min    ${it.video.minResolution} @ ${it.video.minBitRate} bps")
                    log("  pixels/s     ${it.video.maxPixelsPerSecond}")
                    log("  audio        ${it.audio.maxSampleRate} Hz, ${it.audio.maxChannels} ch, " +
                        "${it.audio.minBitRate}-${it.audio.maxBitRate} bps")
                }
                a.display?.let { log("  display      ${it.dimensions} ratio=${it.aspectRatio} scaling=${it.scaling}") }
                offer.firstVideoStream()?.resolutions?.firstOrNull()?.let { offered ->
                    val effective = a.effectiveResolution(offered)
                    if (effective != offered) log("  encode at    $effective (receiver display is smaller in both axes)")
                }
                println()
                log("SUCCESS: the full control path works on this device.")
            }
            is AnswerResult.Error -> {
                log("ANSWER ERROR code=${result.error.code}: ${result.error.description}")
                log("the receiver refused the offer; try fewer codecs or a lower resolution.")
            }
            is AnswerResult.Invalid -> log("ANSWER could not be parsed: ${result.reason}")
        }

        if (keepSeconds > 0) {
            log("staying connected for ${keepSeconds}s to catch late messages ...")
            Thread.sleep(keepSeconds * 1000)
        }
        messenger.close()
    } catch (e: Exception) {
        println()
        log("ABORTED: ${e::class.simpleName}: ${e.message}")
    } finally {
        if (sendStop && sessionId != null) {
            log(">>> STOP $sessionId")
            channel.stop(sessionId)
            Thread.sleep(300)
        }
        channel.closeVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)
        channel.close()
        log("done.")
    }
}
