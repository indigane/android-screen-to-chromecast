package app.castmirror.core.cast.channel

/**
 * Cast v2 namespace URNs and the reserved endpoint ids.
 *
 * Values transcribed from `openscreen:cast/common/channel/message_util.h:20-43` and
 * `openscreen:cast/streaming/message_fields.h:20-23`.
 */
object Namespaces {
    /** Prefix shared by all Google-internal Cast namespaces. */
    const val CAST_INTERNAL_PREFIX = "urn:x-cast:com.google.cast."

    /** Prefix shared by all transport-level namespaces. */
    const val TRANSPORT_PREFIX = "urn:x-cast:com.google.cast.tp."

    /** Device authentication. A sender may skip this entirely (spec §2.5). */
    const val AUTH = "urn:x-cast:com.google.cast.tp.deviceauth"

    /** PING/PONG keep-alive. */
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"

    /** Virtual-connection CONNECT/CLOSE. */
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"

    /** Platform receiver control: LAUNCH, STOP, GET_STATUS, GET_APP_AVAILABILITY. */
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"

    /** Broadcast namespace. */
    const val BROADCAST = "urn:x-cast:com.google.cast.broadcast"

    /** Media playback control. The mirroring app emits an informational MEDIA_STATUS on it. */
    const val MEDIA = "urn:x-cast:com.google.cast.media"

    /** Device setup. */
    const val SETUP = "urn:x-cast:com.google.cast.setup"

    /** Receiver discovery. */
    const val DISCOVERY = "urn:x-cast:com.google.cast.receiver.discovery"

    /** OFFER/ANSWER, GET_CAPABILITIES/CAPABILITIES_RESPONSE. The namespace that matters. */
    const val WEBRTC = "urn:x-cast:com.google.cast.webrtc"

    /** Media-remoting RPC/INPUT. A mirroring sender ignores inbound messages here. */
    const val REMOTING = "urn:x-cast:com.google.cast.remoting"

    /** The reserved platform sender id. */
    const val PLATFORM_SENDER_ID = "sender-0"

    /** The reserved platform receiver id. */
    const val PLATFORM_RECEIVER_ID = "receiver-0"

    /** The broadcast wildcard destination. */
    const val BROADCAST_ID = "*"
}
