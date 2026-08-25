package app.castmirror.core.cast.channel

import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj

/**
 * The `senderInfo` sub-object of a `CONNECT` payload.
 *
 * Field names and values are taken from GMS's `ConnectionControlChannel`. Note the spelling:
 * Google's own sender writes **`sdkType`**, not openscreen's `kMessageKeySdkType` constant value
 * `"skdType"`. That constant is a typo in openscreen that never reached the wire.
 *
 * @property sdkType 1 in GMS (the Android Cast SDK); openscreen-style senders use 2.
 * @property version sender version string. GMS puts its numeric GMS version code here.
 * @property platform 1 in GMS (Android).
 * @property systemVersion `Build.VERSION.RELEASE` on Android; `null` omits the key.
 * @property model `Build.MODEL` on Android; `null` omits the key.
 * @property connectionType 1 when the receiver is local/on-network, 2 otherwise.
 * @property browserVersion emitted (as `""`) only by openscreen-style senders.
 * @property alsoEmitSkdTypeAlias additionally emit openscreen's misspelled `skdType` alongside
 *   `sdkType`. Off by default now that GMS has settled the question; kept as a belt-and-braces
 *   escape hatch for an unusual receiver.
 */
data class SenderInfo(
    val sdkType: Int = 1,
    val version: String = "0",
    val platform: Int = 1,
    val systemVersion: String? = null,
    val model: String? = null,
    val connectionType: Int = 1,
    val browserVersion: String? = null,
    val alsoEmitSkdTypeAlias: Boolean = false,
) {
    /** Serializes in GMS's field order. */
    fun toJson(): Json.Obj = jsonObj {
        put("sdkType", sdkType)
        if (alsoEmitSkdTypeAlias) put("skdType", sdkType)
        put("version", version)
        put("platform", platform)
        systemVersion?.let { put("systemVersion", it) }
        model?.let { put("model", it) }
        browserVersion?.let { put("browserVersion", it) }
        put("connectionType", connectionType)
    }
}

/**
 * The `CONNECT` payload sent on `urn:x-cast:com.google.cast.tp.connection`.
 *
 * A single TLS socket multiplexes many virtual connections keyed by `(source_id, destination_id)`,
 * and **nothing** may be sent to an endpoint before one is opened to it.
 *
 * @property userAgent free-text sender identification. GMS sends
 *   `"Android CastSDK,<gmsVersionCode>,<Build.MODEL>,<Build.PRODUCT>,<Build.VERSION.RELEASE>"`.
 * @property originPackage the value of `origin.package`. GMS names the calling app's package;
 *   `null` sends an empty `origin` object, which is what old Chrome did and what still works.
 * @property senderInfo optional telemetry; `null` omits the key entirely.
 * @property multiplexed GMS's `multiplexed` flag; `null` omits the key.
 * @property protocolVersion GMS sends 3; the negotiated value is clamped to `[0,3]`. `null` omits.
 * @property connType openscreen's `connType` (0 = strong). GMS does not send it; `null` omits.
 */
data class ConnectParams(
    val userAgent: String,
    val originPackage: String? = null,
    val senderInfo: SenderInfo? = null,
    val multiplexed: Boolean? = null,
    val protocolVersion: Int? = null,
    val connType: Int? = null,
) {
    /** Serializes the `CONNECT` payload. */
    fun toJson(): Json.Obj = jsonObj {
        put("type", ReceiverMessageTypes.CONNECT)
        put("origin", if (originPackage == null) Json.Obj() else jsonObj { put("package", originPackage) })
        put("userAgent", userAgent)
        senderInfo?.let { put("senderInfo", it.toJson()) }
        multiplexed?.let { put("multiplexed", it) }
        protocolVersion?.let { put("protocolVersion", it) }
        connType?.let { put("connType", it) }
    }

    companion object {
        /** Version reported by openscreen-style senders. */
        const val OPENSCREEN_SENDER_VERSION = "19.0.0"

        /**
         * The CONNECT GMS's system mirroring sends.
         *
         * The core is Android-free, so the device strings have to be injected by the Android
         * layer; the defaults are honest placeholders rather than a spoofed device.
         *
         * @param appPackage the calling app's package name, for `origin.package`.
         * @param senderVersion the value GMS fills with its version code.
         * @param deviceModel `Build.MODEL`.
         * @param deviceProduct `Build.PRODUCT`.
         * @param androidRelease `Build.VERSION.RELEASE`.
         * @param localNetwork true when the receiver is on the local network (`connectionType` 1).
         */
        fun gms(
            appPackage: String = "app.castmirror",
            senderVersion: String = "1",
            deviceModel: String = "castmirror",
            deviceProduct: String = "castmirror",
            androidRelease: String = "15",
            localNetwork: Boolean = true,
        ): ConnectParams = ConnectParams(
            userAgent = "Android CastSDK,$senderVersion,$deviceModel,$deviceProduct,$androidRelease",
            originPackage = appPackage,
            senderInfo = SenderInfo(
                sdkType = 1,
                version = senderVersion,
                platform = 1,
                systemVersion = androidRelease,
                model = deviceModel,
                connectionType = if (localNetwork) 1 else 2,
            ),
            multiplexed = false,
            protocolVersion = 3,
        )

        /** The CONNECT an openscreen/Chrome-style sender sends. */
        fun openscreen(userAgent: String = "castmirror/1.0 (Linux; Android)"): ConnectParams =
            ConnectParams(
                userAgent = userAgent,
                originPackage = null,
                senderInfo = SenderInfo(
                    sdkType = 2,
                    version = OPENSCREEN_SENDER_VERSION,
                    platform = 2,
                    browserVersion = "",
                    connectionType = 1,
                ),
                connType = 0,
            )

        /** The smallest CONNECT known to work: `type`, `origin`, `userAgent` and nothing else. */
        fun minimal(userAgent: String = "castmirror"): ConnectParams = ConnectParams(userAgent)
    }
}
