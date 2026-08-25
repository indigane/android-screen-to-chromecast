package app.castmirror.core.cast.channel

import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Callbacks for asynchronous channel events. */
interface CastChannelListener {
    /** Every inbound message, after built-in PING/PONG handling. */
    fun onMessage(message: CastMessage) {}

    /** An unsolicited or solicited `RECEIVER_STATUS`. */
    fun onReceiverStatus(status: ReceiverStatus) {}

    /** A `CLOSE` on `tp.connection` from the given source endpoint. */
    fun onVirtualConnectionClosed(sourceId: String) {}

    /** The channel died: socket error, protocol error, or peer disconnect. */
    fun onClosed(cause: Throwable?) {}
}

/**
 * A Cast v2 control channel: framed `CastMessage` protobufs over one TLS socket.
 *
 * Threading model, deliberately minimal:
 *  * one **reader** thread that de-frames messages and dispatches them,
 *  * one **writer** thread draining a queue, so callers never block on the socket,
 *  * an optional **heartbeat** thread sending PING every [heartbeatIntervalMs].
 *
 * Requests carry a monotonically increasing `requestId` and are correlated with the reply that
 * echoes it. Inbound PINGs are answered with PONG automatically — receivers do ping senders.
 *
 * A single socket multiplexes many *virtual connections*, keyed by `(source_id, destination_id)`.
 * You must [openVirtualConnection] to an endpoint before any application message to it will be
 * honoured; failing to do so for the app's `transportId` is the single most common bug in a
 * mirroring sender (spec §2.6).
 *
 * @property socket the transport; owned by this channel and closed by [close].
 * @property senderId the platform-level source id, conventionally `sender-0`.
 * @property connectParams the `CONNECT` payload shape; defaults to GMS's.
 */
class CastChannel(
    private val socket: CastSocket,
    val senderId: String = Namespaces.PLATFORM_SENDER_ID,
    val connectParams: ConnectParams = ConnectParams.gms(),
    private val heartbeatIntervalMs: Long = 5_000,
) : AutoCloseable {

    /** The `userAgent` string sent in CONNECT. */
    val userAgent: String get() = connectParams.userAgent

    private val listeners = CopyOnWriteArrayList<CastChannelListener>()
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextRequestId = AtomicInteger(1)
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var heartbeatThread: Thread? = null

    /** Millis since epoch of the last inbound message of any kind; used for liveness checks. */
    @Volatile
    var lastInboundMs: Long = System.currentTimeMillis()
        private set

    /**
     * Optional pre-decode hook on every inbound frame, invoked with a copy of the raw protobuf
     * body *before* it is decoded into a [CastMessage].
     *
     * `null` by default and off the normal path entirely. It is the capture tool's window onto the
     * exact bytes a real receiver sends, which is the only way to see a field the decoder does not
     * model. Set it before [start] (or at any time — it is read per frame). Runs on the reader
     * thread; keep it fast and non-throwing.
     */
    @Volatile
    var onRawFrame: ((ByteArray) -> Unit)? = null

    /** Registers [l] for asynchronous events. */
    fun addListener(l: CastChannelListener) { listeners.add(l) }

    /** Unregisters [l]. */
    fun removeListener(l: CastChannelListener) { listeners.remove(l) }

    /** Allocates the next `requestId`. */
    fun nextRequestId(): Int = nextRequestId.getAndIncrement()

    /**
     * Starts the reader/writer threads (and the heartbeat, unless [heartbeatIntervalMs] is `<= 0`).
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        writerThread = Thread({ writeLoop() }, "cast-channel-writer").apply { isDaemon = true; start() }
        readerThread = Thread({ readLoop() }, "cast-channel-reader").apply { isDaemon = true; start() }
        if (heartbeatIntervalMs > 0) {
            heartbeatThread = Thread({ heartbeatLoop() }, "cast-channel-heartbeat")
                .apply { isDaemon = true; start() }
        }
    }

    // ---- sending ---------------------------------------------------------------------------

    /** Enqueues [message] for transmission. */
    fun send(message: CastMessage) {
        if (closed.get()) throw IOException("channel is closed")
        writeQueue.put(message.encodeFramed())
    }

    /** Enqueues a JSON payload on [namespace] addressed to [destinationId]. */
    fun sendJson(
        namespace: String,
        destinationId: String,
        payload: Json.Obj,
        sourceId: String = senderId,
    ) = send(
        CastMessage(
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payload.encode(),
        )
    )

    /**
     * Sends a request carrying a fresh `requestId` and blocks for the matching reply.
     *
     * @param accept predicate selecting which echoing reply completes the request. `LAUNCH`
     *   needs this because `LAUNCH_STATUS` is an intermediate reply that must not resolve it.
     * @throws IOException on timeout or channel death.
     */
    fun request(
        namespace: String,
        destinationId: String,
        payload: Json.Obj,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        sourceId: String = senderId,
        accept: (Json.Obj) -> Boolean = { true },
    ): Json.Obj {
        val id = nextRequestId()
        payload.put("requestId", id)
        val p = Pending(accept)
        pending[id] = p
        try {
            sendJson(namespace, destinationId, payload, sourceId)
            return p.await(timeoutMs)
                ?: throw IOException("timed out after ${timeoutMs}ms waiting for reply to requestId=$id")
        } finally {
            pending.remove(id)
        }
    }

    // ---- tp.connection ---------------------------------------------------------------------

    /**
     * Opens a virtual connection to [destinationId].
     *
     * The payload shape comes from [connectParams]; the default matches what Google's Android Cast
     * sender sends, for compatibility with Google's Cast receiver. Note the `sdkType` vs `skdType`
     * spelling: openscreen's constant `kMessageKeySdkType` has the *value* `"skdType"`, which looks
     * like a baked-in typo, but Google's own sender writes **`sdkType`** and real receivers accept
     * it. `senderInfo` is optional telemetry in any case; a CONNECT carrying only
     * `type`/`origin`/`userAgent` also works.
     */
    fun openVirtualConnection(destinationId: String, sourceId: String = senderId) {
        sendJson(Namespaces.CONNECTION, destinationId, connectParams.toJson(), sourceId)
    }

    /** Closes a virtual connection to [destinationId]. */
    fun closeVirtualConnection(destinationId: String, sourceId: String = senderId) {
        if (closed.get()) return
        try {
            sendJson(
                Namespaces.CONNECTION,
                destinationId,
                jsonObj { put("type", ReceiverMessageTypes.CLOSE) },
                sourceId,
            )
        } catch (_: IOException) {
            // Best-effort teardown.
        }
    }

    // ---- tp.heartbeat ----------------------------------------------------------------------

    /**
     * Sends an empty `DeviceAuthMessage{ challenge: AuthChallenge{} }` and ignores the reply.
     *
     * **Normally you should never call this.** The *sender* is the party that issues the auth
     * challenge and the receiver replies with its own certificate chain; the receiver never
     * challenges the sender, so a non-GMS sender needs no certificate, no private key and
     * nothing from Google. Skipping `deviceauth` entirely works, and [SSP] lists authentication
     * as an explicit non-goal of the streaming protocol.
     *
     * SPEC-UNVERIFIED: whether *any* firmware revision refuses `LAUNCH` without a prior auth
     * exchange (spec §2.5, §11 item 4). All available evidence says no. This method exists as the
     * spec's suggested cheap experiment if you ever meet a receiver that stonewalls: the payload
     * is the two bytes `0A 00` (field 1, length 0) on the `deviceauth` namespace, sent as BINARY.
     */
    fun sendEmptyAuthChallenge() = send(
        CastMessage(
            sourceId = Namespaces.PLATFORM_SENDER_ID,
            destinationId = Namespaces.PLATFORM_RECEIVER_ID,
            namespace = Namespaces.AUTH,
            payloadBinary = byteArrayOf(0x0A, 0x00),
        )
    )

    /** Sends a single `PING` to the platform receiver. */
    fun ping() = sendJson(
        Namespaces.HEARTBEAT,
        Namespaces.PLATFORM_RECEIVER_ID,
        jsonObj { put("type", ReceiverMessageTypes.PING) },
    )

    // ---- receiver namespace ----------------------------------------------------------------

    /** Sends `GET_STATUS` and returns the parsed `RECEIVER_STATUS`. */
    fun getStatus(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): ReceiverStatus {
        val reply = request(
            Namespaces.RECEIVER,
            Namespaces.PLATFORM_RECEIVER_ID,
            jsonObj { put("type", ReceiverMessageTypes.GET_STATUS) },
            timeoutMs,
        ) { it.responseType() == ReceiverMessageTypes.RECEIVER_STATUS }
        return ReceiverStatus.parse(reply)
    }

    /**
     * Sends `GET_APP_AVAILABILITY` for [appIds].
     *
     * @return a map of app id to `APP_AVAILABLE` / `APP_UNAVAILABLE`.
     */
    fun getAppAvailability(
        appIds: List<String>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): Map<String, String> {
        val reply = request(
            Namespaces.RECEIVER,
            Namespaces.PLATFORM_RECEIVER_ID,
            jsonObj {
                put("type", ReceiverMessageTypes.GET_APP_AVAILABILITY)
                put("appId", Json.arrayOfStrings(appIds))
            },
            timeoutMs,
        ) { it.responseType() == ReceiverMessageTypes.GET_APP_AVAILABILITY }
        val avail = reply["availability"]?.asObjOrNull() ?: return emptyMap()
        return avail.entries.mapNotNull { (k, v) -> v.asStringOrNull()?.let { k to it } }.toMap()
    }

    /**
     * Launches receiver application [appId] and waits for the `RECEIVER_STATUS` that names it.
     *
     * @param language BCP-47 language tag sent as `language`; `null` omits it, which is what GMS
     *   does — its LAUNCH payload is exactly `{"type","appId","requestId"}`.
     * @param supportedAppTypes app-hosting kinds this sender can drive; `null` omits the key.
     *   GMS omits it. Chrome sends `["WEB","ANDROID_TV"]`, and whether that changes which app an
     *   Android-TV receiver resolves was an open question in the spec — GMS's silence suggests it
     *   is not needed for `674A0243`.
     *
     * @throws CastProtocolException on `LAUNCH_ERROR` / `INVALID_REQUEST`.
     */
    fun launch(
        appId: String,
        language: String? = null,
        supportedAppTypes: List<String>? = null,
        timeoutMs: Long = LAUNCH_TIMEOUT_MS,
    ): ReceiverStatus {
        val payload = jsonObj {
            put("type", ReceiverMessageTypes.LAUNCH)
            put("appId", appId)
            language?.let { put("language", it) }
            supportedAppTypes?.let { put("supportedAppTypes", Json.arrayOfStrings(it)) }
        }
        val reply = request(Namespaces.RECEIVER, Namespaces.PLATFORM_RECEIVER_ID, payload, timeoutMs) {
            when (it.responseType()) {
                // LAUNCH_STATUS is an intermediate progress reply; keep waiting.
                ReceiverMessageTypes.LAUNCH_STATUS -> false
                ReceiverMessageTypes.RECEIVER_STATUS,
                ReceiverMessageTypes.LAUNCH_ERROR,
                ReceiverMessageTypes.INVALID_REQUEST -> true
                else -> false
            }
        }
        when (reply.responseType()) {
            ReceiverMessageTypes.LAUNCH_ERROR, ReceiverMessageTypes.INVALID_REQUEST -> {
                val reason = reply["reason"]?.asStringOrNull()
                throw CastProtocolException("LAUNCH of $appId failed: reason=$reason", reason)
            }
        }
        return ReceiverStatus.parse(reply)
    }

    /** Sends `STOP` for [sessionId]. Best effort: failures during teardown are swallowed. */
    fun stop(sessionId: String) {
        try {
            val payload = jsonObj {
                put("type", ReceiverMessageTypes.STOP)
                put("sessionId", sessionId)
                put("requestId", nextRequestId())
            }
            sendJson(Namespaces.RECEIVER, Namespaces.PLATFORM_RECEIVER_ID, payload)
        } catch (_: IOException) {
            // Best-effort teardown.
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** Closes the socket and stops all threads. Idempotent. */
    override fun close() = shutdown(null)

    private fun shutdown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        // Give already-queued messages (typically STOP and CLOSE) a brief chance to reach the
        // wire before the socket goes away. If the writer is already dead this just times out.
        val deadline = System.currentTimeMillis() + FLUSH_TIMEOUT_MS
        while (writeQueue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2) } catch (_: InterruptedException) { break }
        }
        running.set(false)
        pending.values.forEach { it.fail() }
        pending.clear()
        try { socket.close() } catch (_: Exception) { }
        writeQueue.offer(ByteArray(0)) // wake the writer
        listeners.forEach { runCatching { it.onClosed(cause) } }
    }

    private fun writeLoop() {
        try {
            while (running.get()) {
                val frame = writeQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (frame.isEmpty()) continue
                socket.output.write(frame)
                socket.output.flush()
            }
        } catch (e: Exception) {
            if (running.get()) shutdown(e)
        }
    }

    private fun readLoop() {
        val framer = CastMessageFramer()
        // Read the current hook per frame, so it can be installed before or after start().
        framer.onRawBody = { body -> onRawFrame?.invoke(body) }
        val buf = ByteArray(8192)
        try {
            while (running.get()) {
                val n = socket.input.read(buf)
                if (n < 0) { shutdown(null); return }
                if (n == 0) continue
                framer.append(buf, 0, n)
                while (true) {
                    val msg = framer.next() ?: break
                    lastInboundMs = System.currentTimeMillis()
                    dispatch(msg)
                }
            }
        } catch (e: Exception) {
            if (running.get()) shutdown(e)
        }
    }

    private fun heartbeatLoop() {
        try {
            while (running.get()) {
                Thread.sleep(heartbeatIntervalMs)
                if (!running.get()) return
                try { ping() } catch (_: IOException) { return }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun dispatch(msg: CastMessage) {
        val payload = msg.payloadUtf8?.let { Json.parseOrNull(it) }?.asObjOrNull()

        if (payload != null) {
            when (msg.namespace) {
                Namespaces.HEARTBEAT -> {
                    // Receivers ping senders; answer or the channel is reaped.
                    if (payload["type"]?.asStringOrNull() == ReceiverMessageTypes.PING) {
                        runCatching {
                            sendJson(
                                Namespaces.HEARTBEAT,
                                msg.sourceId.ifEmpty { Namespaces.PLATFORM_RECEIVER_ID },
                                jsonObj { put("type", ReceiverMessageTypes.PONG) },
                                // Reply as whoever the PING was addressed to, but never as the
                                // broadcast wildcard: a PING sent to "*" must still be answered
                                // by a concrete endpoint or the receiver cannot match the PONG
                                // to a virtual connection and reaps the channel.
                                sourceId = msg.destinationId
                                    .takeIf { it.isNotEmpty() && it != Namespaces.BROADCAST_ID }
                                    ?: senderId,
                            )
                        }
                    }
                }
                Namespaces.CONNECTION -> {
                    if (payload["type"]?.asStringOrNull() == ReceiverMessageTypes.CLOSE) {
                        listeners.forEach { runCatching { it.onVirtualConnectionClosed(msg.sourceId) } }
                    }
                }
            }

            val requestId = payload["requestId"]?.asIntOrNull()
            if (requestId != null && requestId != 0) {
                val p = pending[requestId]
                if (p != null && p.accept(payload)) {
                    p.complete(payload)
                    return
                }
            }

            if (payload.responseType() == ReceiverMessageTypes.RECEIVER_STATUS) {
                val status = ReceiverStatus.parse(payload)
                listeners.forEach { runCatching { it.onReceiverStatus(status) } }
            }
        }

        listeners.forEach { runCatching { it.onMessage(msg) } }
    }

    /** Blocking single-slot future used for request/response correlation. */
    private class Pending(val accept: (Json.Obj) -> Boolean) {
        private val lock = ReentrantLock()
        private val changed: Condition = lock.newCondition()
        private var value: Json.Obj? = null
        private var failed = false

        fun complete(v: Json.Obj) = lock.withLock { value = v; changed.signalAll() }

        fun fail() = lock.withLock { failed = true; changed.signalAll() }

        fun await(timeoutMs: Long): Json.Obj? = lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (value == null && !failed) {
                if (remaining <= 0) return null
                remaining = changed.awaitNanos(remaining)
            }
            if (failed) throw IOException("channel closed while awaiting reply")
            return value
        }
    }

    companion object {
        /** Default user agent for an openscreen-style sender. */
        const val DEFAULT_USER_AGENT = "castmirror/1.0 (Linux; Android)"

        /** Default reply timeout for `receiver`-namespace requests. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 5_000L

        /** LAUNCH timeout: spec §2.9 recommends ~10 s. */
        const val LAUNCH_TIMEOUT_MS = 10_000L

        /** How long [close] waits for queued messages to be written before killing the socket. */
        const val FLUSH_TIMEOUT_MS = 500L

        /** Generates a per-session sender id, matching libcast's `MakeUniqueSessionId`. */
        fun uniqueSenderId(prefix: String = "sender"): String =
            "$prefix-${(1000..999_999).random()}"
    }
}

/** Reads the `responseType` (replies) falling back to `type` (requests) of a payload. */
fun Json.Obj.responseType(): String? =
    this["responseType"]?.asStringOrNull() ?: this["type"]?.asStringOrNull()
