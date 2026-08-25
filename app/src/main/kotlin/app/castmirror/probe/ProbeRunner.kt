package app.castmirror.probe

import app.castmirror.core.CastAppIds
import app.castmirror.core.LaunchRung
import app.castmirror.core.cast.channel.CastChannel
import app.castmirror.core.cast.channel.CastChannelListener
import app.castmirror.core.cast.channel.CastMessage
import app.castmirror.core.cast.channel.ConnectParams
import app.castmirror.core.cast.channel.Namespaces
import app.castmirror.core.cast.channel.TlsCastSocket
import app.castmirror.core.cast.streaming.messages.AnswerResult
import app.castmirror.core.cast.streaming.messages.OfferBuilder
import app.castmirror.core.cast.streaming.messages.OfferProfile
import app.castmirror.core.cast.streaming.messages.Resolution
import app.castmirror.core.cast.streaming.messages.SessionMessenger
import app.castmirror.core.discovery.CastDevice
import app.castmirror.core.util.Json
import app.castmirror.util.AppLog

/**
 * Runs the whole Cast control path against a receiver and logs everything it sees, without
 * sending a single media packet.
 *
 * This is the diagnostic that matters on a device nobody has tested against yet. Mirroring has a
 * long list of things that can go wrong before a pixel is encoded — TLS, the two virtual
 * connections, the app id, the OFFER dialect — and every one of them fails silently from the
 * user's point of view. A probe that ends in `ANSWER OK` with a `udpPort` proves the entire
 * control path in about three seconds and leaves the raw `RECEIVER_STATUS` and `ANSWER` JSON in
 * the log, which is what any further diagnosis needs.
 *
 * It is the in-app equivalent of the core's `castprobe` CLI, and follows the same script:
 *
 * ```
 * TLS connect -> CONNECT -> GET_STATUS -> GET_APP_AVAILABILITY -> LAUNCH
 *             -> CONNECT to the app's transportId -> OFFER -> ANSWER -> STOP
 * ```
 */
object ProbeRunner {

    /** Reports probe progress. Called on the probe's own thread. */
    fun interface Callback {
        /** The probe finished; [ok] says whether an ANSWER arrived. */
        fun onProbeFinished(ok: Boolean, summary: String)
    }

    /**
     * Runs a probe on a background thread.
     *
     * @param device the receiver to probe.
     * @param rung the app id and OFFER dialect to try. Only one rung is attempted — the point is
     *   to learn what this specific pairing does, not to find one that works.
     * @param appPackage the sender's package name, sent as `origin.package` in `CONNECT`.
     * @param resolution the resolution to offer, so the ANSWER's `display.dimensions` can be
     *   compared against something realistic.
     * @param callback invoked once, whatever happens.
     */
    fun run(
        device: CastDevice,
        rung: LaunchRung,
        appPackage: String,
        resolution: Resolution,
        callback: Callback,
    ) {
        val t = Thread({ probe(device, rung, appPackage, resolution, callback) }, "castmirror-probe")
        t.isDaemon = true
        t.start()
    }

    private fun probe(
        device: CastDevice,
        rung: LaunchRung,
        appPackage: String,
        resolution: Resolution,
        callback: Callback,
    ) {
        val profile = rung.profile
        AppLog.i("probe: ${device.host}:${device.port} appId=${rung.appId} profile=${profile.cliName}")

        var channel: CastChannel? = null
        var sessionId: String? = null
        try {
            val socket = TlsCastSocket.connect(device.host, device.port)
            AppLog.i("probe: TLS up (${socket.description})")

            // The CONNECT payload follows the dialect too: probing 0F5096E8 with GMS's
            // senderInfo would not reproduce what the real session will send.
            val connectParams = if (profile == OfferProfile.GMS_SYSTEM_MIRRORING) {
                ConnectParams.gms(appPackage = appPackage)
            } else {
                ConnectParams.openscreen()
            }
            val ch = CastChannel(socket, connectParams = connectParams)
            channel = ch
            ch.addListener(object : CastChannelListener {
                override fun onMessage(message: CastMessage) {
                    if (message.namespace == Namespaces.HEARTBEAT) return
                    AppLog.i("probe: <<< ${message.namespace}")
                    AppLog.i(prettyOrRaw(message.payloadUtf8))
                }
            })
            ch.start()

            ch.openVirtualConnection(Namespaces.PLATFORM_RECEIVER_ID)
            AppLog.i("probe: >>> GET_STATUS")
            val status = ch.getStatus()
            AppLog.i("probe: ${status.applications.size} application(s) running")

            AppLog.i("probe: >>> GET_APP_AVAILABILITY ${CastAppIds.ALL_MIRRORING}")
            val availability = runCatching { ch.getAppAvailability(CastAppIds.ALL_MIRRORING) }
                .getOrDefault(emptyMap())
            availability.forEach { (id, value) -> AppLog.i("probe:   $id -> $value") }

            AppLog.i("probe: >>> LAUNCH ${rung.appId}")
            val launched = ch.launch(
                rung.appId,
                language = profile.launchLanguage,
                supportedAppTypes = profile.launchSupportedAppTypes,
            )
            val app = launched.applicationFor(rung.appId)
            val transportId = app?.transportId
            if (app == null || transportId == null) {
                callback.onProbeFinished(false, "LAUNCH succeeded but RECEIVER_STATUS did not name ${rung.appId}")
                return
            }
            sessionId = app.sessionId
            AppLog.i("probe: launched sessionId=${app.sessionId} transportId=$transportId")
            AppLog.i("probe: namespaces ${app.namespaces}")
            if (Namespaces.WEBRTC !in app.namespaces) {
                AppLog.w("probe: the app does not advertise ${Namespaces.WEBRTC}; the OFFER will likely be ignored")
            }

            // The second virtual connection, to the app rather than the platform receiver.
            // Omitting it makes the receiver silently drop the OFFER.
            val senderId = CastChannel.uniqueSenderId()
            ch.openVirtualConnection(transportId, senderId)
            val messenger = SessionMessenger(
                channel = ch,
                sourceId = senderId,
                destinationId = transportId,
                initialSeqNum = profile.initialSeqNum,
            )

            val offer = OfferBuilder.build(
                profile = profile,
                videoCodecs = profile.videoCodecs,
                audioCodecs = emptyList(),
                resolutions = listOf(resolution),
            )
            AppLog.i("probe: >>> OFFER (${offer.streams.size} stream(s))")
            AppLog.i(pretty(offer.toMessage(messenger.peekSeqNum())))

            when (val result = messenger.sendOffer(offer)) {
                is AnswerResult.Ok -> {
                    val answer = result.answer
                    val mediaHost = answer.ipaddr ?: device.host
                    AppLog.i("probe: ANSWER OK — send RTP to $mediaHost:${answer.udpPort}")
                    AppLog.i("probe:   sendIndexes=${answer.sendIndexes} ssrcs=${answer.ssrcs}")
                    answer.constraints?.let {
                        AppLog.i("probe:   video max ${it.video.maxDimensions} @ ${it.video.maxBitRate} bps")
                        AppLog.i("probe:   maxPixelsPerSecond ${it.video.maxPixelsPerSecond}")
                    }
                    answer.display?.let { AppLog.i("probe:   display ${it.dimensions} scaling=${it.scaling}") }
                    val effective = answer.effectiveResolution(resolution)
                    AppLog.i("probe:   effective encode resolution ${effective.width}x${effective.height}")
                    callback.onProbeFinished(
                        true,
                        "ANSWER OK: udpPort=${answer.udpPort}, encode ${effective.width}x${effective.height}",
                    )
                }

                is AnswerResult.Error -> {
                    AppLog.w("probe: receiver rejected the OFFER: ${result.error.code} ${result.error.description}")
                    callback.onProbeFinished(false, "OFFER rejected: ${result.error.description}")
                }

                is AnswerResult.Invalid -> {
                    AppLog.w("probe: malformed ANSWER: ${result.reason}")
                    callback.onProbeFinished(false, "Malformed ANSWER: ${result.reason}")
                }
            }
        } catch (e: Throwable) {
            AppLog.w("probe: failed", e)
            callback.onProbeFinished(false, e.message ?: e.javaClass.simpleName)
        } finally {
            // Leave the receiver on its idle screen rather than on a stalled mirroring app.
            sessionId?.let { id -> runCatching { channel?.stop(id) } }
            runCatching { channel?.close() }
            AppLog.i("probe: finished")
        }
    }

    private fun prettyOrRaw(payload: String?): String =
        payload?.let { Json.parseOrNull(it)?.let(::pretty) ?: it } ?: "<binary payload>"

    /** Two-space-indented JSON, so a raw `RECEIVER_STATUS` is readable in the log view. */
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
}
