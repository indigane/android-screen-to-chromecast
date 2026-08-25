package app.castmirror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import app.castmirror.core.discovery.CastDevice
import app.castmirror.core.discovery.MdnsDiscovery
import app.castmirror.util.AppLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Finds `_googlecast._tcp` receivers on the local network.
 *
 * Two independent paths run at once and their results are merged:
 *
 *  * **[NsdManager]** — the AOSP resolver. It is present on GrapheneOS, needs no permission, and
 *    keeps working when the app is not holding a multicast lock. Its weakness is the TXT record:
 *    `getAttributes()` is empty or partial on a number of ROMs and Android versions, and the
 *    Cast launch decision depends on TXT (`ca` picks the app id, `fn` is the only human-readable
 *    name). Its other weakness is that `resolveService` is single-flight below API 34 and fails
 *    with `FAILURE_ALREADY_ACTIVE` if a second resolve is started while one is outstanding, so
 *    resolves are queued here rather than fired in parallel.
 *
 *  * **The core's pure-Java mDNS client** — a `MulticastSocket` that parses the DNS-SD records
 *    itself and therefore returns the TXT record verbatim. It needs
 *    `CHANGE_WIFI_MULTICAST_STATE` and a held [WifiManager.MulticastLock], because Wi-Fi hardware
 *    filters multicast frames when no lock is held.
 *
 * When both paths see the same receiver, the record with more TXT keys wins — that is almost
 * always the fallback's, and it is the one that carries `ca`.
 *
 * mDNS is routinely broken by AP client isolation, mesh routers and VLANs, so [addManual] exists
 * as an escape hatch: a receiver entered by IP is merged into the same list.
 */
class DeviceDiscovery(context: Context) {

    /** Receives the merged device list whenever it changes. Callbacks arrive on the main thread. */
    fun interface Listener {
        /** [devices] is the full current list, sorted by friendly name. */
        fun onDevicesChanged(devices: List<CastDevice>)
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Merged results, keyed by `host:port`. */
    private val found = LinkedHashMap<String, CastDevice>()

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolveInFlight = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var fallbackThread: Thread? = null

    @Volatile
    private var running = false

    /** Subscribes [l] and immediately delivers the current list. */
    fun addListener(l: Listener) {
        listeners.add(l)
        val snapshot = sortedDevices()
        main.post { runCatching { l.onDevicesChanged(snapshot) } }
    }

    /** Unsubscribes [l]. */
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Starts both discovery paths. Idempotent. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        startNsd()
        startFallback()
    }

    /** Stops both discovery paths and releases the multicast lock. Idempotent. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stopNsd()
        fallbackThread?.interrupt()
        fallbackThread = null
        releaseMulticastLock()
    }

    /** Clears the accumulated list and re-runs discovery from scratch. */
    fun refresh() {
        synchronized(found) { found.clear() }
        publish()
        stop()
        start()
    }

    /**
     * Adds a receiver the user typed in by hand.
     *
     * Nothing is known about it beyond the address, so it is recorded with `ca` claiming video
     * output — the overwhelmingly common case, and the one that picks the A/V app id. If the
     * device turns out to be a speaker the LAUNCH simply fails and the log says so.
     *
     * @param host an IPv4/IPv6 literal or a hostname.
     * @param port the Cast control port; every Google receiver publishes 8009.
     */
    fun addManual(host: String, port: Int = DEFAULT_CAST_PORT) {
        val device = CastDevice(
            instanceName = host,
            friendlyName = "$host (manual)",
            model = null,
            host = host,
            port = port,
            deviceId = null,
            capabilities = MANUAL_CAPABILITIES,
            protocolVersion = null,
            status = null,
            statusText = null,
            txt = emptyMap(),
        )
        AppLog.i("discovery: manually added $host:$port")
        merge(device)
    }

    // ---- NsdManager ------------------------------------------------------------------------

    private fun startNsd() {
        val nsd = nsdManager ?: run {
            AppLog.w("discovery: NsdManager unavailable; relying on the mDNS fallback only")
            return
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w("discovery: NSD start failed (error $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w("discovery: NSD stop failed (error $errorCode)")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                AppLog.i("discovery: NSD browsing $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // The address is not populated on a lost event, so there is no reliable key to
                // remove. Leaving a stale entry is better than dropping a live receiver; the
                // Refresh button clears the list.
            }
        }
        discoveryListener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { AppLog.w("discovery: discoverServices threw", it) }
    }

    private fun stopNsd() {
        val nsd = nsdManager ?: return
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolveInFlight = false
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            if (resolveQueue.any { it.serviceName == info.serviceName }) return
            resolveQueue.addLast(info)
            if (resolveInFlight) return
            resolveInFlight = true
        }
        pumpResolveQueue()
    }

    /**
     * Resolves one queued service at a time.
     *
     * `NsdManager.resolveService` is documented as single-flight below API 34: a second call while
     * one is outstanding fails with `FAILURE_ALREADY_ACTIVE`, and on several ROMs it wedges the
     * resolver until the process restarts.
     */
    private fun pumpResolveQueue() {
        val nsd = nsdManager ?: return
        val next = synchronized(resolveQueue) {
            val head = resolveQueue.removeFirstOrNull()
            if (head == null) resolveInFlight = false
            head
        } ?: return

        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.w("discovery: NSD resolve of ${serviceInfo.serviceName} failed (error $errorCode)")
                pumpResolveQueue()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                fromNsd(serviceInfo)?.let(::merge)
                pumpResolveQueue()
            }
        }
        // resolveService is deprecated in favour of registerServiceInfoCallback, which is API 34
        // only; this app supports API 26 upward, so the deprecated call is the one that works
        // everywhere. Queuing it here already handles the single-flight limitation that the
        // replacement was introduced to remove.
        @Suppress("DEPRECATION")
        runCatching { nsd.resolveService(next, callback) }.onFailure {
            AppLog.w("discovery: resolveService threw", it)
            pumpResolveQueue()
        }
    }

    /**
     * Converts a resolved [NsdServiceInfo] into a [CastDevice].
     *
     * `CastDevice.from` rejects a record with no `fn` key, which is correct for a raw mDNS TXT
     * record but wrong here: NsdManager frequently reports an empty attribute map even though the
     * receiver is perfectly usable. The DNS-SD instance name is the receiver's own label, so it is
     * substituted for `fn` and the device is kept.
     */
    private fun fromNsd(info: NsdServiceInfo): CastDevice? {
        @Suppress("DEPRECATION")
        val address = info.host ?: return null
        val port = info.port
        if (port !in 1..65535) return null

        val txt = HashMap<String, String>()
        for ((key, value) in info.attributes) {
            if (value == null) continue
            txt[key] = String(value, Charsets.UTF_8)
        }
        if (txt["fn"].isNullOrEmpty()) txt["fn"] = info.serviceName ?: address.hostAddress.orEmpty()
        val host = address.hostAddress ?: return null
        if (txt.size <= 1) {
            AppLog.i("discovery: NSD gave no TXT for ${info.serviceName}; the mDNS fallback will fill it in")
        }
        return CastDevice.from(info.serviceName ?: host, host, port, txt)
    }

    // ---- pure-Java mDNS fallback -------------------------------------------------------------

    private fun startFallback() {
        val t = Thread(::runFallbackLoop, "castmirror-mdns")
        t.isDaemon = true
        fallbackThread = t
        t.start()
    }

    /**
     * Repeatedly runs a blocking mDNS pass for as long as this thread is the active one.
     *
     * `MdnsDiscovery.discover` polls its socket with a short read timeout and returns at its own
     * deadline, so an interrupt is only observed at the sleep between passes: a stopped thread can
     * still be inside a pass when [start] is called again. Checking [fallbackThread] rather than
     * [running] is what stops that older thread from resurrecting itself and leaving two loops
     * competing for the multicast lock.
     */
    private fun runFallbackLoop() {
        acquireMulticastLock()
        try {
            while (isActiveFallbackThread() && !Thread.currentThread().isInterrupted) {
                val devices = try {
                    MdnsDiscovery.discover(timeoutMs = FALLBACK_LISTEN_MS)
                } catch (e: Throwable) {
                    AppLog.w("discovery: mDNS fallback failed", e)
                    emptyList()
                }
                if (devices.isNotEmpty() && isActiveFallbackThread()) {
                    AppLog.i("discovery: mDNS fallback resolved ${devices.size} receiver(s)")
                    devices.forEach(::merge)
                }
                try {
                    Thread.sleep(FALLBACK_IDLE_MS)
                } catch (e: InterruptedException) {
                    return
                }
            }
        } finally {
            releaseMulticastLock()
        }
    }

    private fun isActiveFallbackThread(): Boolean =
        running && fallbackThread === Thread.currentThread()

    private fun acquireMulticastLock() {
        val wifi = wifiManager ?: return
        if (multicastLock != null) return
        val lock = runCatching { wifi.createMulticastLock("castmirror") }.getOrNull() ?: return
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
            .onFailure { AppLog.w("discovery: could not acquire the multicast lock", it) }
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    // ---- merging ---------------------------------------------------------------------------

    private fun merge(device: CastDevice) {
        val key = "${device.host}:${device.port}"
        val changed = synchronized(found) {
            val existing = found[key]
            // The record carrying more TXT keys is the more useful one: `ca` decides the app id
            // and `md` is the only generation hint available.
            if (existing != null && existing.txt.size >= device.txt.size) false
            else {
                found[key] = device
                true
            }
        }
        if (changed) publish()
    }

    private fun sortedDevices(): List<CastDevice> =
        synchronized(found) { found.values.sortedBy { it.friendlyName.lowercase() } }

    private fun publish() {
        val snapshot = sortedDevices()
        main.post { listeners.forEach { runCatching { it.onDevicesChanged(snapshot) } } }
    }

    companion object {
        /** The DNS-SD service type, in the form NsdManager expects. */
        const val SERVICE_TYPE = "_googlecast._tcp"

        /** The port every Google-manufactured receiver publishes in its SRV record. */
        const val DEFAULT_CAST_PORT = 8009

        /** `VIDEO_OUT | AUDIO_OUT`, assumed for a manually entered address. */
        private const val MANUAL_CAPABILITIES = 0x05

        private const val FALLBACK_LISTEN_MS = 4_000L
        private const val FALLBACK_IDLE_MS = 3_000L
    }
}
