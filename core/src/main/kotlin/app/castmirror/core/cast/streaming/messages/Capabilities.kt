package app.castmirror.core.cast.streaming.messages

import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj

/**
 * The body of a `CAPABILITIES_RESPONSE`.
 *
 * Only relevant to media remoting, but harmless and occasionally informative.
 *
 * **Never choose a mirroring codec from [mediaCaps]**: [SSP] warns that "some legacy receivers
 * may report `vp9` and `hevc` in their `mediaCaps` response, even if they cannot remote these
 * codecs", and `video` is deprecated and ignored. Offer several codecs and let the receiver pick.
 *
 * @property remoting remoting protocol version; absent means 1. libcast supports up to 2.
 * @property mediaCaps tokens such as `audio`, `aac`, `opus`, `video`, `4k`, `h264`, `vp8`,
 *   `vp9`, `hevc`, `av1`. The `audio` token is magic and means "the baseline codec set".
 */
data class ReceiverCapabilities(
    val remoting: Int = 1,
    val mediaCaps: List<String> = emptyList(),
) {
    companion object {
        /** Parses a `capabilities` body object. */
        fun parse(o: Json.Obj): ReceiverCapabilities = ReceiverCapabilities(
            remoting = o["remoting"]?.asIntOrNull() ?: 1,
            mediaCaps = o["mediaCaps"]?.asArrOrNull()?.items?.mapNotNull { it.asStringOrNull() }
                ?: emptyList(),
        )
    }
}

/** Message type strings on `urn:x-cast:com.google.cast.webrtc` and `...remoting`. */
object StreamingMessageTypes {
    /** Sender to receiver: the stream offer. */
    const val OFFER = "OFFER"

    /** Sender to receiver: ask what the receiver can remote. */
    const val GET_CAPABILITIES = "GET_CAPABILITIES"

    /** Sender to receiver, or receiver to sender: media-remoting RPC (base64 protobuf). */
    const val RPC = "RPC"

    /** Receiver to sender: remote-control input events (base64 protobuf). */
    const val INPUT = "INPUT"

    /** Receiver to sender: the reply to [OFFER]. */
    const val ANSWER = "ANSWER"

    /** Receiver to sender: the reply to [GET_CAPABILITIES]. */
    const val CAPABILITIES_RESPONSE = "CAPABILITIES_RESPONSE"

    /**
     * Receiver to sender: periodic streaming statistics.
     *
     * `OFFER`, `ANSWER` and `STATISTICS` are the entire vocabulary GMS's mirroring implements.
     * GMS parses these and throws them away — its own statistics come from the native sender — so
     * this core does the same, reporting them to [SessionMessengerListener.onStatistics] for
     * anyone who wants them.
     */
    const val STATISTICS = "STATISTICS"

    /** `result` value for success. */
    const val RESULT_OK = "ok"

    /** `result` value for failure. */
    const val RESULT_ERROR = "error"

    /**
     * `GET_STATUS` / `STATUS_RESPONSE` and `PRESENTATION` on the webrtc namespace are deliberately
     * not implemented here.
     *
     * SPEC-UNVERIFIED: both are enumerated in openscreen's `CastMessageType` but have no parser
     * or serializer anywhere in libcast, and no primary source documents their bodies
     * (spec §4.9, §11 item 8). `PRESENTATION` is additionally listed as deprecated by [SSP].
     * Neither is needed for mirroring; inbound instances fall through to
     * [SessionMessengerListener.onUnhandled].
     */
    const val UNIMPLEMENTED_NOTE = "GET_STATUS/STATUS_RESPONSE/PRESENTATION are not implemented"
}

/**
 * An `RPC` message on the remoting namespace.
 *
 * A screen-mirroring sender needs none of this; inbound RPCs are simply dropped. The type is
 * provided so that a caller who wants remoting later has the envelope handling already done.
 *
 * Note that RPC and INPUT carry **no** `seqNum` and **no** `result` field.
 *
 * @property base64Payload the base64-encoded `remoting.proto` message.
 */
data class RpcMessage(val base64Payload: String) {
    /** Serializes `{"type":"RPC","rpc":"<base64>"}`. */
    fun toJson(): Json.Obj = jsonObj {
        put("type", StreamingMessageTypes.RPC)
        put("rpc", base64Payload)
    }

    companion object {
        /** Parses an RPC envelope, or `null` if it is not one. */
        fun parse(o: Json.Obj): RpcMessage? {
            if (o["type"]?.asStringOrNull()?.uppercase() != StreamingMessageTypes.RPC) return null
            val body = o["rpc"]?.asStringOrNull() ?: return null
            return RpcMessage(body)
        }
    }
}
