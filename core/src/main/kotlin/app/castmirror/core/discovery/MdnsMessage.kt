package app.castmirror.core.discovery

import app.castmirror.core.util.ByteWriter
import app.castmirror.core.util.Bytes

/** DNS resource-record types used by DNS-SD. */
object DnsType {
    /** IPv4 address. */
    const val A = 1

    /** Canonical name. */
    const val CNAME = 5

    /** Pointer: service type to service instance. */
    const val PTR = 12

    /** Arbitrary key/value text. */
    const val TXT = 16

    /** IPv6 address. */
    const val AAAA = 28

    /** Service location: priority, weight, port, target. */
    const val SRV = 33

    /** Wildcard query type. */
    const val ANY = 255
}

/** DNS class values; mDNS steals the top bit for the unicast-response / cache-flush flag. */
object DnsClass {
    /** The Internet class. */
    const val IN = 1

    /** Mask for the cache-flush (responses) / unicast-response (queries) bit. */
    const val FLAG_BIT = 0x8000
}

/**
 * One parsed resource record.
 *
 * @property name the owner name, dot-joined.
 * @property type see [DnsType].
 * @property dnsClass the record class with the flag bit stripped.
 * @property cacheFlush the mDNS cache-flush bit.
 * @property ttl time to live in seconds.
 * @property rdata the raw record data.
 * @property parsed the decoded record body, when the type is one this parser understands.
 */
class DnsRecord(
    val name: String,
    val type: Int,
    val dnsClass: Int,
    val cacheFlush: Boolean,
    val ttl: Long,
    val rdata: ByteArray,
    val parsed: DnsRecordData?,
)

/** The decoded body of a resource record. */
sealed class DnsRecordData {
    /** A `PTR` record pointing at a service instance name. */
    data class Ptr(
        /** The target instance name. */
        val target: String,
    ) : DnsRecordData()

    /** An `SRV` record. */
    data class Srv(
        /** Priority; lower is preferred. */
        val priority: Int,
        /** Weight for records of equal priority. */
        val weight: Int,
        /** The service port. Chromecasts publish 8009. */
        val port: Int,
        /** The target host name. */
        val target: String,
    ) : DnsRecordData()

    /** A `TXT` record: a list of length-prefixed `key=value` strings. */
    data class Txt(
        /** Decoded key/value pairs; keys are lower-cased. A bare key maps to `""`. */
        val entries: Map<String, String>,
    ) : DnsRecordData()

    /** An `A` record. */
    data class A(
        /** Dotted-quad IPv4 address. */
        val address: String,
    ) : DnsRecordData()

    /** An `AAAA` record. */
    data class Aaaa(
        /** Colon-separated IPv6 address. */
        val address: String,
    ) : DnsRecordData()
}

/** One question from a DNS message. */
class DnsQuestion(
    /** The queried name, dot-joined. */
    val name: String,
    /** See [DnsType]. */
    val type: Int,
    /** The record class with the flag bit stripped. */
    val dnsClass: Int,
    /** The mDNS unicast-response bit. */
    val unicastResponse: Boolean,
)

/**
 * A parsed DNS/mDNS message.
 *
 * @property id the transaction id; always 0 for multicast DNS.
 * @property flags the 16-bit flags word.
 * @property questions the question section.
 * @property answers the answer section.
 * @property authorities the authority section.
 * @property additionals the additional section, where mDNS responders put the SRV/TXT/A records
 *   that accompany a PTR answer.
 */
class DnsMessage(
    val id: Int,
    val flags: Int,
    val questions: List<DnsQuestion>,
    val answers: List<DnsRecord>,
    val authorities: List<DnsRecord>,
    val additionals: List<DnsRecord>,
) {
    /** Every record from every section. */
    fun allRecords(): List<DnsRecord> = answers + authorities + additionals

    /** True when this is a response (QR bit set). */
    fun isResponse(): Boolean = (flags and 0x8000) != 0
}

/**
 * A hand-rolled DNS wire-format codec, sufficient for mDNS service discovery.
 *
 * Android's `NsdManager` is the primary discovery path on device; this parser backs the pure-Java
 * fallback (which is also what you want when you need `md` and `ca` verbatim, since
 * `NsdManager`'s TXT attributes are unreliable on Android 8-11 and its resolves are serialised).
 * Being an ordinary parser it is equally useful for tests and for a desktop CLI.
 *
 * Name compression (RFC 1035 section 4.1.4) is supported on read, with a pointer-loop guard.
 * Names are written uncompressed, which is always legal.
 */
object MdnsCodec {

    /** The multicast group and port mDNS uses. */
    const val MDNS_PORT = 5353

    /** The IPv4 mDNS multicast group. */
    const val MDNS_GROUP_IPV4 = "224.0.0.251"

    /**
     * Builds a standard multicast query.
     *
     * @param names names to ask about, e.g. `_googlecast._tcp.local`.
     * @param type the record type; `PTR` for service enumeration.
     * @param unicastResponse sets the QU bit, asking for a unicast reply. Useful on networks
     *   where multicast replies are filtered, but responders may ignore it.
     */
    fun buildQuery(names: List<String>, type: Int = DnsType.PTR, unicastResponse: Boolean = false): ByteArray {
        val w = ByteWriter(64)
        w.u16(0)              // ID: always 0 for mDNS.
        w.u16(0)              // Flags: standard query.
        w.u16(names.size)     // QDCOUNT
        w.u16(0)              // ANCOUNT
        w.u16(0)              // NSCOUNT
        w.u16(0)              // ARCOUNT
        for (n in names) {
            writeName(w, n)
            w.u16(type)
            w.u16(if (unicastResponse) DnsClass.IN or DnsClass.FLAG_BIT else DnsClass.IN)
        }
        return w.toByteArray()
    }

    /**
     * Parses a DNS message.
     *
     * @throws IllegalArgumentException on malformed input.
     */
    fun parse(data: ByteArray, length: Int = data.size): DnsMessage {
        require(length >= 12) { "DNS message shorter than its header" }
        val id = Bytes.u16(data, 0)
        val flags = Bytes.u16(data, 2)
        val qd = Bytes.u16(data, 4)
        val an = Bytes.u16(data, 6)
        val ns = Bytes.u16(data, 8)
        val ar = Bytes.u16(data, 10)

        val cursor = Cursor(12)
        val questions = ArrayList<DnsQuestion>(qd)
        repeat(qd) {
            val name = readName(data, length, cursor)
            require(cursor.pos + 4 <= length) { "truncated question" }
            val type = Bytes.u16(data, cursor.pos)
            val klass = Bytes.u16(data, cursor.pos + 2)
            cursor.pos += 4
            questions.add(DnsQuestion(name, type, klass and DnsClass.FLAG_BIT.inv(), (klass and DnsClass.FLAG_BIT) != 0))
        }

        fun readRecords(count: Int): List<DnsRecord> {
            val out = ArrayList<DnsRecord>(count)
            repeat(count) {
                val name = readName(data, length, cursor)
                require(cursor.pos + 10 <= length) { "truncated resource record" }
                val type = Bytes.u16(data, cursor.pos)
                val klass = Bytes.u16(data, cursor.pos + 2)
                val ttl = Bytes.u32(data, cursor.pos + 4)
                val rdLength = Bytes.u16(data, cursor.pos + 8)
                cursor.pos += 10
                require(cursor.pos + rdLength <= length) { "truncated rdata" }
                val rdataStart = cursor.pos
                val rdata = data.copyOfRange(rdataStart, rdataStart + rdLength)
                val parsed = parseRdata(type, data, length, rdataStart, rdLength)
                cursor.pos = rdataStart + rdLength
                out.add(
                    DnsRecord(
                        name = name,
                        type = type,
                        dnsClass = klass and DnsClass.FLAG_BIT.inv(),
                        cacheFlush = (klass and DnsClass.FLAG_BIT) != 0,
                        ttl = ttl,
                        rdata = rdata,
                        parsed = parsed,
                    )
                )
            }
            return out
        }

        val answers = readRecords(an)
        val authorities = readRecords(ns)
        val additionals = readRecords(ar)
        return DnsMessage(id, flags, questions, answers, authorities, additionals)
    }

    private fun parseRdata(type: Int, data: ByteArray, length: Int, start: Int, rdLength: Int): DnsRecordData? =
        when (type) {
            DnsType.PTR -> DnsRecordData.Ptr(readName(data, length, Cursor(start)))
            DnsType.SRV -> {
                if (rdLength < 7) null
                else DnsRecordData.Srv(
                    priority = Bytes.u16(data, start),
                    weight = Bytes.u16(data, start + 2),
                    port = Bytes.u16(data, start + 4),
                    target = readName(data, length, Cursor(start + 6)),
                )
            }
            DnsType.TXT -> DnsRecordData.Txt(parseTxt(data, start, rdLength))
            DnsType.A ->
                if (rdLength != 4) null
                else DnsRecordData.A(
                    (0 until 4).joinToString(".") { Bytes.u8(data, start + it).toString() }
                )
            DnsType.AAAA ->
                if (rdLength != 16) null
                else DnsRecordData.Aaaa(
                    (0 until 8).joinToString(":") { String.format("%x", Bytes.u16(data, start + it * 2)) }
                )
            else -> null
        }

    /**
     * Parses a TXT record's length-prefixed strings into a map.
     *
     * Keys are lower-cased (DNS-SD says keys are case-insensitive); a string with no `=` maps to
     * an empty value. Duplicate keys keep the first occurrence, as RFC 6763 requires.
     */
    fun parseTxt(data: ByteArray, start: Int, rdLength: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pos = start
        val end = start + rdLength
        while (pos < end) {
            val len = Bytes.u8(data, pos)
            pos++
            if (len == 0) continue
            if (pos + len > end) break
            val s = String(data, pos, len, Charsets.UTF_8)
            pos += len
            val eq = s.indexOf('=')
            val key = (if (eq < 0) s else s.substring(0, eq)).lowercase()
            val value = if (eq < 0) "" else s.substring(eq + 1)
            out.putIfAbsent(key, value)
        }
        return out
    }

    /** Writes a dot-separated name in uncompressed DNS label form. */
    fun writeName(w: ByteWriter, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 63) { "DNS label too long: $label" }
            w.u8(bytes.size)
            w.bytes(bytes)
        }
        w.u8(0)
    }

    /** Mutable read position, so that name decompression can rewind without losing the cursor. */
    private class Cursor(var pos: Int)

    /**
     * Reads a possibly-compressed name, advancing [cursor] past the *encoded* name only.
     *
     * Compression pointers are followed into the message, but the cursor stops just after the
     * first pointer, which is what the wire format requires.
     */
    private fun readName(data: ByteArray, length: Int, cursor: Cursor): String {
        val labels = ArrayList<String>()
        var pos = cursor.pos
        var jumped = false
        var guard = 0
        while (true) {
            require(guard++ < MAX_NAME_JUMPS) { "DNS name compression loop" }
            require(pos < length) { "truncated DNS name" }
            val len = Bytes.u8(data, pos)
            if (len == 0) {
                pos++
                if (!jumped) cursor.pos = pos
                break
            }
            if ((len and 0xC0) == 0xC0) {
                require(pos + 1 < length) { "truncated compression pointer" }
                val target = ((len and 0x3F) shl 8) or Bytes.u8(data, pos + 1)
                if (!jumped) cursor.pos = pos + 2
                jumped = true
                require(target < pos) { "forward compression pointer" }
                pos = target
                continue
            }
            require(pos + 1 + len <= length) { "truncated DNS label" }
            labels.add(String(data, pos + 1, len, Charsets.UTF_8))
            pos += 1 + len
        }
        return labels.joinToString(".")
    }

    private const val MAX_NAME_JUMPS = 128
}
