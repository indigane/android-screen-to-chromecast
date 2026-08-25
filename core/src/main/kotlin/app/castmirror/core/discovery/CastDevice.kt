package app.castmirror.core.discovery

/**
 * Capability bits from the mDNS `ca` TXT key.
 *
 * openscreen defines only the low five bits (`receiver_info.h:46-53`); bits 11 and 12 are named
 * in Chromium's default device-capability constant (`kDefaultDeviceCapabilities = 6149`).
 *
 * SPEC-UNVERIFIED: bits 5-10 and 13+ are used by the closed-source Cast SDK and their meanings
 * are not documented in any open source (spec §1.3, §11 item 7).
 */
object CastCapabilities {
    /** The device has a display. */
    const val VIDEO_OUT = 0x0001

    /** The device has a camera. */
    const val VIDEO_IN = 0x0002

    /** The device has speakers. */
    const val AUDIO_OUT = 0x0004

    /** The device has a microphone. */
    const val AUDIO_IN = 0x0008

    /** Developer mode is enabled. */
    const val DEV_MODE = 0x0010

    /** Master-or-fixed volume control. */
    const val MASTER_OR_FIXED_VOLUME = 0x0800

    /** Attenuation-or-fixed volume control. */
    const val ATTENUATION_OR_FIXED_VOLUME = 0x1000
}

/**
 * A discovered Cast receiver.
 *
 * @property instanceName the DNS-SD instance name; equal to the `id` TXT value on Google hardware.
 * @property friendlyName the `fn` TXT value, e.g. "Living Room TV". Required by openscreen.
 * @property model the `md` TXT value, e.g. "Chromecast Ultra". Optional — some third-party
 *   receivers omit it — and the best available generation-detection hint. Treat it as a logging
 *   heuristic only and rely on runtime probing instead.
 * @property host the resolved address from the `A`/`AAAA` record.
 * @property port the `SRV` port. **Always use this**, never a hard-coded constant; in practice
 *   every Google-manufactured receiver publishes 8009.
 * @property deviceId the `id` TXT value: 32 hex digits.
 * @property capabilities the `ca` bitfield.
 * @property protocolVersion the `ve` TXT value; openscreen rejects anything below 2 or above 99.
 * @property status the `st` TXT value: `0` idle, `1` busy/joinable.
 * @property statusText the `rs` TXT value — free text describing the running app, empty when idle.
 *   SPEC-UNVERIFIED: semantics beyond "current app display name" (spec §1.2).
 * @property txt every TXT key/value verbatim, including the keys openscreen ignores
 *   (`bs`, `nf`, `ic`, `cd`, `rm`, `pk`), whose semantics are SPEC-UNVERIFIED (spec §11 item 6).
 */
data class CastDevice(
    val instanceName: String,
    val friendlyName: String,
    val model: String?,
    val host: String,
    val port: Int,
    val deviceId: String?,
    val capabilities: Int,
    val protocolVersion: Int?,
    val status: Int?,
    val statusText: String?,
    val txt: Map<String, String> = emptyMap(),
) {
    /**
     * True when `ca` was absent or unparseable, so nothing is actually known about this device's
     * outputs.
     *
     * No real Cast receiver advertises `ca=0` — it would claim no display, no speakers, no
     * microphone and no camera. A zero here therefore always means "the TXT record never reached
     * us", which on Android is routine: `NsdManager.getAttributes()` returns an empty map on a
     * number of ROMs, and the pure-Java mDNS fallback needs a multicast lock that may not be
     * grantable.
     */
    fun capabilitiesUnknown(): Boolean = capabilities == 0

    /**
     * True when the device has a display and can accept an audio + video mirroring offer.
     *
     * **Unknown capabilities are treated as "has a display."** This decides the receiver app id
     * (`674A0243` vs `8E6C866D`, GMS `bfrg.java`), and launching the audio-only mirroring app on
     * a TV produces a session that negotiates but shows nothing. Video output is by far the
     * common case, and the failure is asymmetric: guessing "video" on a speaker yields a clean
     * `LAUNCH_ERROR`, while guessing "audio-only" on a TV yields a silent black screen.
     */
    fun hasVideoOutput(): Boolean =
        capabilitiesUnknown() || (capabilities and CastCapabilities.VIDEO_OUT) != 0

    /** True when the device has speakers, or when nothing is known about it. */
    fun hasAudioOutput(): Boolean =
        capabilitiesUnknown() || (capabilities and CastCapabilities.AUDIO_OUT) != 0

    /**
     * True for a Cast-for-audio device (Google Home, Nest Audio/Mini), which will reject an
     * offer containing only video streams.
     */
    fun isAudioOnly(): Boolean = !hasVideoOutput() && hasAudioOutput()

    /** True when an activity is running and a sender is invited to join it. */
    fun isBusy(): Boolean = status == 1

    override fun toString(): String =
        "$friendlyName [$model] $host:$port ca=0x${capabilities.toString(16)} id=$deviceId"

    companion object {
        /** The DNS-SD service type. */
        const val SERVICE_TYPE = "_googlecast._tcp"

        /** The DNS-SD domain. */
        const val DOMAIN = "local"

        /** The fully qualified service name queried for a PTR. */
        const val SERVICE_NAME = "$SERVICE_TYPE.$DOMAIN"

        /**
         * Builds a device from resolved DNS-SD data.
         *
         * Parsing is deliberately tolerant: a missing optional key never rejects a device, and
         * unknown keys are preserved in [txt]. `md` in particular must not be required.
         *
         * @return the device, or `null` when a hard requirement (a friendly name and an address)
         *   is missing.
         */
        fun from(instanceName: String, host: String, port: Int, txt: Map<String, String>): CastDevice? {
            val friendlyName = txt["fn"]?.takeIf { it.isNotEmpty() } ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return CastDevice(
                instanceName = instanceName,
                friendlyName = friendlyName,
                model = txt["md"],
                host = host,
                port = port,
                deviceId = txt["id"],
                capabilities = txt["ca"]?.trim()?.toIntOrNull() ?: 0,
                protocolVersion = txt["ve"]?.trim()?.toIntOrNull(),
                status = txt["st"]?.trim()?.toIntOrNull(),
                statusText = txt["rs"],
                txt = txt,
            )
        }
    }
}
