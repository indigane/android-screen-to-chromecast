package app.castmirror.core.discovery

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Accumulates DNS-SD records across responses and resolves complete service instances.
 *
 * mDNS responders usually pack PTR + SRV + TXT + A into one datagram, but they are not obliged
 * to, so records are collected and instances become resolvable once their SRV, TXT and address
 * records have all arrived. Purely in-memory and side-effect free, so it is directly testable
 * against hand-built packets.
 */
class MdnsResolver(private val serviceName: String = CastDevice.SERVICE_NAME) {

    private val instances = LinkedHashSet<String>()
    private val srv = HashMap<String, DnsRecordData.Srv>()
    private val txt = HashMap<String, Map<String, String>>()
    private val ipv4 = HashMap<String, String>()
    private val ipv6 = HashMap<String, String>()

    /** Feeds one parsed DNS message in. */
    fun accept(message: DnsMessage) {
        for (r in message.allRecords()) {
            when (val d = r.parsed) {
                is DnsRecordData.Ptr ->
                    if (matchesService(r.name)) instances.add(d.target)

                is DnsRecordData.Srv -> {
                    srv[r.name] = d
                    if (matchesService(parentOf(r.name))) instances.add(r.name)
                }

                is DnsRecordData.Txt -> {
                    txt[r.name] = d.entries
                    if (matchesService(parentOf(r.name))) instances.add(r.name)
                }

                is DnsRecordData.A -> ipv4.putIfAbsent(r.name, d.address)

                is DnsRecordData.Aaaa -> ipv6.putIfAbsent(r.name, d.address)

                else -> Unit
            }
        }
    }

    /** Feeds one raw datagram in, ignoring anything that fails to parse. */
    fun acceptPacket(data: ByteArray, length: Int = data.size) {
        runCatching { MdnsCodec.parse(data, length) }.getOrNull()?.let { accept(it) }
    }

    /** Every instance that now has enough records to resolve. */
    fun devices(): List<CastDevice> = instances.mapNotNull { resolve(it) }

    /**
     * Resolves a single instance name, or `null` if records are still missing.
     *
     * IPv4 wins over IPv6 regardless of the order the records arrived in: Cast senders connect
     * over IPv4 in practice, and an IPv6 literal needs bracketing everywhere downstream.
     */
    fun resolve(instance: String): CastDevice? {
        val s = srv[instance] ?: return null
        val t = txt[instance] ?: return null
        val host = ipv4[s.target] ?: ipv6[s.target] ?: return null
        val shortName = instance.removeSuffix(".$serviceName").removeSuffix(".")
        return CastDevice.from(shortName, host, s.port, t)
    }

    private fun matchesService(name: String): Boolean =
        name.trimEnd('.').equals(serviceName, ignoreCase = true)

    private fun parentOf(name: String): String {
        val dot = name.indexOf('.')
        return if (dot < 0) "" else name.substring(dot + 1)
    }
}

/**
 * A pure-JVM mDNS client for `_googlecast._tcp.local`.
 *
 * On Android this is the **fallback** path — `NsdManager` is AOSP, works on GrapheneOS and is the
 * primary — but it is worth having, because it returns the TXT record verbatim (you want `md`
 * and `ca` exactly as published) and avoids `NsdManager`'s serialised resolves and its unreliable
 * TXT attributes on Android 8-11.
 *
 * On Android this requires `CHANGE_WIFI_MULTICAST_STATE` and a held `MulticastLock`, or Wi-Fi
 * firmware filters multicast while the screen is off. Always also offer a manual "enter an IP"
 * escape hatch: mDNS is routinely broken by AP client isolation, mesh routers and VLANs.
 */
object MdnsDiscovery {

    /**
     * Runs a blocking discovery pass.
     *
     * Sends a PTR query for [serviceName], then collects responses until [timeoutMs] elapses.
     * The query is re-sent every second, since the first one is often lost on a busy network.
     *
     * @param timeoutMs total time to spend listening.
     * @param serviceName the DNS-SD service to enumerate.
     * @param networkInterface the interface to join the multicast group on; `null` lets the OS
     *   choose, which is usually wrong on a multi-homed host.
     * @return every device that resolved completely.
     */
    fun discover(
        timeoutMs: Long = 5_000,
        serviceName: String = CastDevice.SERVICE_NAME,
        networkInterface: NetworkInterface? = null,
    ): List<CastDevice> {
        val resolver = MdnsResolver(serviceName)
        val group = InetAddress.getByName(MdnsCodec.MDNS_GROUP_IPV4)
        val query = MdnsCodec.buildQuery(listOf(serviceName))

        MulticastSocket(MdnsCodec.MDNS_PORT).use { socket ->
            socket.reuseAddress = true
            socket.timeToLive = 255
            if (networkInterface != null) socket.networkInterface = networkInterface
            socket.joinGroup(InetSocketAddress(group, MdnsCodec.MDNS_PORT), networkInterface)
            socket.soTimeout = 250

            val deadline = System.currentTimeMillis() + timeoutMs
            var nextQueryAt = 0L
            val buffer = ByteArray(9000)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextQueryAt) {
                    runCatching {
                        socket.send(DatagramPacket(query, query.size, group, MdnsCodec.MDNS_PORT))
                    }
                    nextQueryAt = now + 1_000
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    resolver.acceptPacket(packet.data, packet.length)
                } catch (_: java.net.SocketTimeoutException) {
                    // Keep looping until the deadline.
                }
            }
            runCatching { socket.leaveGroup(InetSocketAddress(group, MdnsCodec.MDNS_PORT), networkInterface) }
        }
        return resolver.devices()
    }
}
