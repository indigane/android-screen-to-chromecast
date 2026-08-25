package app.castmirror.core.cast.streaming.messages

import app.castmirror.core.StreamingConstants
import app.castmirror.core.cast.channel.CastChannel
import app.castmirror.core.cast.channel.CastChannelListener
import app.castmirror.core.cast.channel.CastMessage
import app.castmirror.core.cast.channel.Namespaces
import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Callbacks for unsolicited streaming-namespace traffic. */
interface SessionMessengerListener {
    /** An `RPC` arrived on the remoting namespace. Mirroring senders ignore these. */
    fun onRpc(rpc: RpcMessage) {}

    /** An `INPUT` arrived. Draft feature, gated behind the `input_events` extension. */
    fun onInput(base64Payload: String) {}

    /**
     * A `STATISTICS` message arrived. Purely informational: GMS parses these and discards them.
     */
    fun onStatistics(payload: Json.Obj) {}

    /** Any streaming-namespace message that was not a reply to an outstanding request. */
    fun onUnhandled(payload: Json.Obj) {}
}

/**
 * OFFER/ANSWER messaging over an established [CastChannel].
 *
 * Session messages ride on `urn:x-cast:com.google.cast.webrtc` (and `...remoting` for RPC/INPUT),
 * addressed from the per-session sender id to the launched app's `transportId`. libcast hard-
 * asserts that nothing else is sent on these namespaces.
 *
 * Replies are matched **purely on `seqNum`**; a reply carrying an unknown `seqNum` is dropped.
 * `seqNum` must be non-negative; Chrome starts from a large random value. Outgoing messages also
 * carry `requestId` equal to that `seqNum`, which is what GMS's channel adapter puts on the wire;
 * inbound replies are never matched on it.
 *
 * The reply timeout is 4000 ms, exactly libcast's `kReplyTimeout`.
 *
 * @param channel an already-started control channel.
 * @param sourceId the per-session sender id, e.g. `sender-8371`. Must match the source used for
 *   the virtual connection to [destinationId].
 * @param destinationId the launched app's `transportId`.
 * @param initialSeqNum starting sequence number. GMS starts at **1** and passes the same integer
 *   as the Cast `requestId`; Chrome starts from a large random value. Any negative value means
 *   "pick a large random start", which is how [OfferProfile.OPENSCREEN_CHROME] expresses it.
 */
class SessionMessenger(
    private val channel: CastChannel,
    private val sourceId: String,
    private val destinationId: String,
    initialSeqNum: Int = 1,
) : AutoCloseable {

    private val seq = AtomicInteger(
        if (initialSeqNum >= 0) initialSeqNum else (1..1_000_000_000).random()
    )
    private val pending = ConcurrentHashMap<Int, Slot>()
    private var listener: SessionMessengerListener? = null

    private val channelListener = object : CastChannelListener {
        override fun onMessage(message: CastMessage) = handle(message)
        override fun onClosed(cause: Throwable?) {
            pending.values.forEach { it.fail(cause) }
            pending.clear()
        }
    }

    init {
        channel.addListener(channelListener)
    }

    /** Registers a listener for unsolicited streaming messages. */
    fun setListener(l: SessionMessengerListener?) { listener = l }

    /** The next `seqNum` that will be used. */
    fun peekSeqNum(): Int = seq.get()

    /**
     * Sends [offer] and waits up to [timeoutMs] for the matching `ANSWER`.
     *
     * @return the parse result: [AnswerResult.Ok], [AnswerResult.Error] (the receiver refused the
     *   offer — terminate unless you have a simpler fallback offer) or [AnswerResult.Invalid].
     * @throws IOException if no reply arrives before the timeout, which libcast reports as
     *   `kAnswerTimeout` and treats as a fatal session error.
     */
    fun sendOffer(offer: Offer, timeoutMs: Long = StreamingConstants.REPLY_TIMEOUT_MS): AnswerResult {
        val n = nextSeqNum()
        val reply = exchange(Namespaces.WEBRTC, offer.toMessage(n), n, timeoutMs)
        return AnswerParser.parse(reply)
    }

    /**
     * Sends `GET_CAPABILITIES` and waits for `CAPABILITIES_RESPONSE`.
     *
     * A timeout or `result: "error"` simply means "no remoting", which is irrelevant to a
     * mirroring sender — hence `null` rather than an exception on failure.
     */
    fun getCapabilities(timeoutMs: Long = StreamingConstants.REPLY_TIMEOUT_MS): ReceiverCapabilities? {
        val n = nextSeqNum()
        val payload = jsonObj {
            put("type", StreamingMessageTypes.GET_CAPABILITIES)
            put("seqNum", n)
        }
        val reply = try {
            exchange(Namespaces.WEBRTC, payload, n, timeoutMs)
        } catch (_: IOException) {
            return null
        }
        if (reply["result"]?.asStringOrNull() == StreamingMessageTypes.RESULT_ERROR) return null
        return reply["capabilities"]?.asObjOrNull()?.let(ReceiverCapabilities::parse)
    }

    /** Sends an `RPC` on the remoting namespace. No reply is expected. */
    fun sendRpc(rpc: RpcMessage) =
        channel.sendJson(Namespaces.REMOTING, destinationId, rpc.toJson(), sourceId)

    /** Detaches from the channel. Does not close the channel itself. */
    override fun close() {
        channel.removeListener(channelListener)
        pending.values.forEach { it.fail(null) }
        pending.clear()
    }

    /** Returns the next `seqNum`, wrapping to 1 rather than ever going negative. */
    private fun nextSeqNum(): Int {
        while (true) {
            val cur = seq.get()
            val next = if (cur == Int.MAX_VALUE) 1 else cur + 1
            if (seq.compareAndSet(cur, next)) return cur
        }
    }

    private fun exchange(namespace: String, payload: Json.Obj, seqNum: Int, timeoutMs: Long): Json.Obj {
        val slot = Slot()
        pending[seqNum] = slot
        try {
            // GMS's mirroring layer builds the envelope without a requestId — `behq.o` Gson-
            // serializes `CastMirroringMessage(type, seqNum, offer, …)` and nothing else — and
            // then hands it to the Cast channel adapter, which stamps the Cast v2 `requestId`
            // onto the payload: `beho.m(json, seqNum, transportId)` -> `befr.m` ->
            // `begv.b(namespace, msg, requestId, transportId)`. The mirroring `seqNum` and that
            // `requestId` are deliberately the same integer, so the message on the wire carries
            // both. Stamping it here rather than in `Offer.toMessage` keeps the OFFER body
            // byte-identical to the reference OFFER and puts the key at the layer that owns it.
            //
            // Safe for the openscreen dialect too: `SenderMessage::Parse` (sender_message.cc:41)
            // reads only `seqNum`, `type` and the body key by name, so an unknown key is ignored,
            // and the normative spec's OFFER table does not forbid extra fields.
            payload.put("requestId", seqNum)
            channel.sendJson(namespace, destinationId, payload, sourceId)
            return slot.await(timeoutMs)
                ?: throw IOException("no reply within ${timeoutMs}ms for seqNum=$seqNum")
        } finally {
            pending.remove(seqNum)
        }
    }

    private fun handle(message: CastMessage) {
        if (message.namespace != Namespaces.WEBRTC && message.namespace != Namespaces.REMOTING) return
        val payload = message.payloadUtf8?.let { Json.parseOrNull(it) }?.asObjOrNull() ?: return
        // Message types are upper-cased before matching, as receiver_message.cc does.
        val type = payload["type"]?.asStringOrNull()?.uppercase()

        // RPC and INPUT carry no seqNum and no result.
        when (type) {
            StreamingMessageTypes.RPC -> {
                RpcMessage.parse(payload)?.let { r -> listener?.onRpc(r) }
                return
            }
            StreamingMessageTypes.INPUT -> {
                payload["input"]?.asStringOrNull()?.let { listener?.onInput(it) }
                return
            }
            StreamingMessageTypes.STATISTICS -> {
                // Parsed then dropped, exactly as GMS does.
                listener?.onStatistics(payload)
                return
            }
        }

        val seqNum = payload["seqNum"]?.asIntOrNull()
        if (seqNum != null && seqNum >= 0) {
            val slot = pending[seqNum]
            if (slot != null) { slot.complete(payload); return }
        }
        listener?.onUnhandled(payload)
    }

    /** Blocking single-slot future for one outstanding `seqNum`. */
    private class Slot {
        private val lock = ReentrantLock()
        private val changed: Condition = lock.newCondition()
        private var value: Json.Obj? = null
        private var failure: Throwable? = null
        private var dead = false

        fun complete(v: Json.Obj) = lock.withLock { value = v; changed.signalAll() }

        fun fail(cause: Throwable?) = lock.withLock { dead = true; failure = cause; changed.signalAll() }

        fun await(timeoutMs: Long): Json.Obj? = lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (value == null && !dead) {
                if (remaining <= 0) return null
                remaining = changed.awaitNanos(remaining)
            }
            if (dead && value == null) throw IOException("channel closed while awaiting reply", failure)
            return value
        }
    }
}
