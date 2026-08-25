package app.castmirror.core.cast.channel

import app.castmirror.core.util.Json

/**
 * One entry of `RECEIVER_STATUS.status.applications[]`.
 *
 * @property appId the receiver application id, e.g. `0F5096E8`.
 * @property transportId the endpoint id to address app-level messages to. This becomes
 *   `destination_id` for OFFER/ANSWER.
 * @property sessionId the id passed back to the platform receiver in `STOP`.
 * @property displayName human-readable app name, e.g. "Chrome Mirroring".
 * @property statusText free-text status shown on screen.
 * @property namespaces namespaces the app advertises.
 * @property isIdleScreen true for the Backdrop/idle application — never your session.
 */
data class ReceiverApplication(
    val appId: String,
    val transportId: String?,
    val sessionId: String?,
    val displayName: String? = null,
    val statusText: String? = null,
    val namespaces: List<String> = emptyList(),
    val isIdleScreen: Boolean = false,
) {
    companion object {
        /** Parses one application object; returns `null` if `appId` is missing. */
        fun parse(o: Json.Obj): ReceiverApplication? {
            val appId = o["appId"]?.asStringOrNull() ?: return null
            val namespaces = o["namespaces"]?.asArrOrNull()?.items
                ?.mapNotNull { it.asObjOrNull()?.get("name")?.asStringOrNull() }
                ?: emptyList()
            return ReceiverApplication(
                appId = appId,
                transportId = o["transportId"]?.asStringOrNull(),
                sessionId = o["sessionId"]?.asStringOrNull(),
                displayName = o["displayName"]?.asStringOrNull(),
                statusText = o["statusText"]?.asStringOrNull(),
                namespaces = namespaces,
                isIdleScreen = o["isIdleScreen"]?.asBoolOrNull() ?: false,
            )
        }
    }
}

/** Receiver volume state from `RECEIVER_STATUS.status.volume`. */
data class ReceiverVolume(
    /** `"attenuation"`, `"fixed"`, or absent. */
    val controlType: String? = null,
    /** Volume in `[0,1]`. */
    val level: Double? = null,
    /** Mute state. */
    val muted: Boolean? = null,
    /** Volume step granularity. */
    val stepInterval: Double? = null,
) {
    companion object {
        /** Parses a volume object. */
        fun parse(o: Json.Obj): ReceiverVolume = ReceiverVolume(
            controlType = o["controlType"]?.asStringOrNull(),
            level = o["level"]?.asDoubleOrNull(),
            muted = o["muted"]?.asBoolOrNull(),
            stepInterval = o["stepInterval"]?.asDoubleOrNull(),
        )
    }
}

/**
 * A parsed `RECEIVER_STATUS` message.
 *
 * These arrive both as replies (echoing your `requestId`) and unsolicited with `requestId: 0`
 * whenever receiver state changes. An unsolicited status that no longer lists your `sessionId`
 * means the session was terminated externally.
 */
data class ReceiverStatus(
    /** Echoed request id, or 0 for an unsolicited broadcast. */
    val requestId: Int,
    /** Currently running applications. */
    val applications: List<ReceiverApplication>,
    /** Volume state, when reported. */
    val volume: ReceiverVolume? = null,
) {
    /**
     * Finds the application matching [appId], ignoring case as libcast's `EqualsIgnoreCase` does,
     * and skipping the idle screen.
     */
    fun applicationFor(appId: String): ReceiverApplication? =
        applications.firstOrNull { !it.isIdleScreen && it.appId.equals(appId, ignoreCase = true) }

    companion object {
        /** Parses a `RECEIVER_STATUS` payload object. */
        fun parse(payload: Json.Obj): ReceiverStatus {
            val status = payload["status"]?.asObjOrNull()
            val apps = status?.get("applications")?.asArrOrNull()?.items
                ?.mapNotNull { it.asObjOrNull()?.let(ReceiverApplication::parse) }
                ?: emptyList()
            return ReceiverStatus(
                requestId = payload["requestId"]?.asIntOrNull() ?: 0,
                applications = apps,
                volume = status?.get("volume")?.asObjOrNull()?.let(ReceiverVolume::parse),
            )
        }
    }
}

/** Message type / response type strings on the `receiver` and `tp.*` namespaces. */
object ReceiverMessageTypes {
    /** Open a virtual connection. */
    const val CONNECT = "CONNECT"

    /** Close a virtual connection. */
    const val CLOSE = "CLOSE"

    /** Optional receiver acknowledgement of CONNECT; do not wait for it. */
    const val CONNECTED = "CONNECTED"

    /** Keep-alive request. */
    const val PING = "PING"

    /** Keep-alive reply. */
    const val PONG = "PONG"

    /** Ask for current receiver status. */
    const val GET_STATUS = "GET_STATUS"

    /** Launch a receiver application. */
    const val LAUNCH = "LAUNCH"

    /** Terminate a session. */
    const val STOP = "STOP"

    /** Probe whether app ids are supported. */
    const val GET_APP_AVAILABILITY = "GET_APP_AVAILABILITY"

    /** Receiver status broadcast/reply. */
    const val RECEIVER_STATUS = "RECEIVER_STATUS"

    /** Optional intermediate launch progress reply. */
    const val LAUNCH_STATUS = "LAUNCH_STATUS"

    /** Launch failed. */
    const val LAUNCH_ERROR = "LAUNCH_ERROR"

    /** The receiver rejected a malformed request. */
    const val INVALID_REQUEST = "INVALID_REQUEST"

    /** `availability` map value: the app can be launched. */
    const val APP_AVAILABLE = "APP_AVAILABLE"

    /** `availability` map value: the app is not supported here. */
    const val APP_UNAVAILABLE = "APP_UNAVAILABLE"

    /** `LAUNCH_ERROR.reason` meaning the app id is unknown to this device — trigger a fallback. */
    const val REASON_NOT_FOUND = "NOT_FOUND"
}

/** Thrown when the receiver replies with `LAUNCH_ERROR` or `INVALID_REQUEST`. */
class CastProtocolException(
    message: String,
    /** The `reason` string from the receiver, when present. */
    val reason: String? = null,
) : RuntimeException(message)
