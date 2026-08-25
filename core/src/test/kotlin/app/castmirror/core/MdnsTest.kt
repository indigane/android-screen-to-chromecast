package app.castmirror.core

import app.castmirror.core.discovery.CastCapabilities
import app.castmirror.core.discovery.CastDevice
import app.castmirror.core.discovery.DnsRecordData
import app.castmirror.core.discovery.DnsType
import app.castmirror.core.discovery.MdnsCodec
import app.castmirror.core.discovery.MdnsResolver
import app.castmirror.core.util.ByteWriter
import app.castmirror.core.util.Bytes

private const val SERVICE = "_googlecast._tcp.local"
private const val INSTANCE = "abcdef0123456789abcdef0123456789.$SERVICE"
private const val TARGET = "abcdef0123456789abcdef0123456789.local"

/** Encodes TXT rdata: each entry is a length-prefixed `key=value` string. */
private fun txtRdata(entries: List<String>): ByteArray {
    val w = ByteWriter(128)
    for (e in entries) {
        val b = e.toByteArray(Charsets.UTF_8)
        w.u8(b.size)
        w.bytes(b)
    }
    return w.toByteArray()
}

/** Appends one uncompressed resource record. */
private fun ByteWriter.record(name: String, type: Int, rdata: ByteArray, ttl: Long = 120, cacheFlush: Boolean = false) {
    MdnsCodec.writeName(this, name)
    u16(type)
    u16(if (cacheFlush) 0x8001 else 0x0001)
    u32(ttl)
    u16(rdata.size)
    bytes(rdata)
}

/** Builds a realistic Chromecast mDNS response: PTR answer plus SRV/TXT/A additionals. */
private fun buildCastResponse(): ByteArray {
    val w = ByteWriter(512)
    w.u16(0)          // ID: always 0 for mDNS
    w.u16(0x8400)     // QR=1 (response), AA=1
    w.u16(0)          // QDCOUNT
    w.u16(1)          // ANCOUNT
    w.u16(0)          // NSCOUNT
    w.u16(3)          // ARCOUNT

    val ptrRdata = ByteWriter(64).also { MdnsCodec.writeName(it, INSTANCE) }.toByteArray()
    w.record(SERVICE, DnsType.PTR, ptrRdata, ttl = 120)

    val srvRdata = ByteWriter(64).also {
        it.u16(0)      // priority
        it.u16(0)      // weight
        it.u16(8009)   // port -- always take this from the SRV record, never hard-code it
        MdnsCodec.writeName(it, TARGET)
    }.toByteArray()
    w.record(INSTANCE, DnsType.SRV, srvRdata, cacheFlush = true)

    w.record(
        INSTANCE,
        DnsType.TXT,
        txtRdata(
            listOf(
                "id=abcdef0123456789abcdef0123456789",
                "cd=00112233445566778899AABBCCDDEEFF",
                "rm=",
                "ve=05",
                "md=Chromecast Ultra",
                "ic=/setup/icon.png",
                "fn=Living Room TV",
                "ca=4101",
                "st=1",
                "bs=1A2B3C4D5E6F",
                "rs=Default Media Receiver",
                "nf=1",
            )
        ),
        cacheFlush = true,
    )

    w.record(TARGET, DnsType.A, byteArrayOf(192.toByte(), 168.toByte(), 1, 50), cacheFlush = true)
    return w.toByteArray()
}

/**
 * Builds a message that uses DNS name compression: the SRV record's owner name is a pointer
 * back to the instance name inside the PTR record's rdata.
 */
private fun buildCompressedResponse(): ByteArray {
    val w = ByteWriter(256)
    w.u16(0); w.u16(0x8400); w.u16(0); w.u16(1); w.u16(0); w.u16(1)

    // The PTR record. Note where its rdata (the instance name) begins so it can be pointed at.
    MdnsCodec.writeName(w, SERVICE)
    w.u16(DnsType.PTR)
    w.u16(0x0001)
    w.u32(120L)
    val ptrRdata = ByteWriter(64).also { MdnsCodec.writeName(it, INSTANCE) }.toByteArray()
    w.u16(ptrRdata.size)
    val instanceNameOffset = w.size
    w.bytes(ptrRdata)

    // The SRV record, whose owner name is a single 2-byte pointer.
    w.u16(0xC000 or instanceNameOffset)
    w.u16(DnsType.SRV)
    w.u16(0x0001)
    w.u32(120L)
    val srvRdata = ByteWriter(32).also {
        it.u16(0); it.u16(0); it.u16(8009)
        MdnsCodec.writeName(it, TARGET)
    }.toByteArray()
    w.u16(srvRdata.size)
    w.bytes(srvRdata)
    return w.toByteArray()
}

/** mDNS query building, response parsing, and device resolution. */
fun runMdnsTests() {
    T.section("mDNS query building")

    val query = MdnsCodec.buildQuery(listOf(SERVICE))
    T.assertEquals("query transaction id is 0", 0, Bytes.u16(query, 0))
    T.assertEquals("query flags are 0", 0, Bytes.u16(query, 2))
    T.assertEquals("one question", 1, Bytes.u16(query, 4))
    T.assertEquals("no answers", 0, Bytes.u16(query, 6))
    // "_googlecast" (11) + "_tcp" (4) + "local" (5) + 3 length bytes + terminator = 24 name bytes.
    T.assertEquals("query length", 12 + 24 + 4, query.size)
    T.assertEquals("first label length", 11, Bytes.u8(query, 12))
    T.assertEquals("question type is PTR", DnsType.PTR, Bytes.u16(query, 12 + 24))
    T.assertEquals("question class is IN", 1, Bytes.u16(query, 12 + 26))

    val parsedQuery = MdnsCodec.parse(query)
    T.check("the query parses back", !parsedQuery.isResponse())
    T.assertEquals("question name round-trips", SERVICE, parsedQuery.questions[0].name)
    T.check("unicast bit is clear by default", !parsedQuery.questions[0].unicastResponse)

    val unicast = MdnsCodec.parse(MdnsCodec.buildQuery(listOf(SERVICE), unicastResponse = true))
    T.check("unicast bit can be set", unicast.questions[0].unicastResponse)
    T.assertEquals("the flag bit is stripped from the class", 1, unicast.questions[0].dnsClass)

    T.section("mDNS response parsing")

    val response = buildCastResponse()
    val message = MdnsCodec.parse(response)
    T.check("parsed as a response", message.isResponse())
    T.assertEquals("one answer", 1, message.answers.size)
    T.assertEquals("three additionals", 3, message.additionals.size)

    val ptr = message.answers[0]
    T.assertEquals("PTR owner is the service name", SERVICE, ptr.name)
    T.assertEquals("PTR type", DnsType.PTR, ptr.type)
    T.assertEquals("PTR ttl", 120L, ptr.ttl)
    T.assertEquals("PTR target is the instance", INSTANCE, (ptr.parsed as DnsRecordData.Ptr).target)

    val srv = message.additionals.first { it.type == DnsType.SRV }.parsed as DnsRecordData.Srv
    T.assertEquals("SRV port", 8009, srv.port)
    T.assertEquals("SRV target host", TARGET, srv.target)
    T.check("cache-flush bit is parsed off the class", message.additionals[0].cacheFlush)

    val txt = message.additionals.first { it.type == DnsType.TXT }.parsed as DnsRecordData.Txt
    T.assertEquals("TXT fn", "Living Room TV", txt.entries["fn"])
    T.assertEquals("TXT md", "Chromecast Ultra", txt.entries["md"])
    T.assertEquals("TXT ca", "4101", txt.entries["ca"])
    T.assertEquals("TXT ve", "05", txt.entries["ve"])
    T.assertEquals("TXT st", "1", txt.entries["st"])
    T.assertEquals("TXT rs", "Default Media Receiver", txt.entries["rs"])
    T.assertEquals("a bare key maps to an empty value", "", txt.entries["rm"])
    T.assertEquals("unrecognised keys are preserved", "1A2B3C4D5E6F", txt.entries["bs"])

    val a = message.additionals.first { it.type == DnsType.A }.parsed as DnsRecordData.A
    T.assertEquals("A record address", "192.168.1.50", a.address)

    T.section("mDNS device resolution")

    val resolver = MdnsResolver()
    resolver.acceptPacket(response)
    val devices = resolver.devices()
    T.assertEquals("one device resolved", 1, devices.size)
    val d = devices[0]
    T.assertEquals("friendly name", "Living Room TV", d.friendlyName)
    T.assertEquals("model", "Chromecast Ultra", d.model)
    T.assertEquals("host", "192.168.1.50", d.host)
    T.assertEquals("port comes from SRV", 8009, d.port)
    T.assertEquals("device id", "abcdef0123456789abcdef0123456789", d.deviceId)
    T.assertEquals("capabilities", 4101, d.capabilities)
    T.assertEquals("protocol version", 5, d.protocolVersion)
    T.check("status is busy", d.isBusy())
    // 4101 = 0x1005 = video out (0x1) | audio out (0x4) | attenuation-or-fixed volume (0x1000).
    T.check("has video output", d.hasVideoOutput())
    T.check("has audio output", d.hasAudioOutput())
    T.check("not audio-only", !d.isAudioOnly())
    T.check("volume capability bit", (d.capabilities and CastCapabilities.ATTENUATION_OR_FIXED_VOLUME) != 0)

    val audioOnly = CastDevice.from("x", "10.0.0.9", 8009, mapOf("fn" to "Nest Mini", "ca" to "4"))!!
    T.check("an audio-only device is detected", audioOnly.isAudioOnly())
    T.check("a device with no md is still accepted", audioOnly.model == null)
    T.check("a device with no fn is rejected", CastDevice.from("x", "10.0.0.9", 8009, mapOf("ca" to "5")) == null)
    T.check("a device with an empty fn is rejected", CastDevice.from("x", "10.0.0.9", 8009, mapOf("fn" to "")) == null)
    T.assertEquals("a missing ca defaults to 0", 0, CastDevice.from("x", "1.2.3.4", 8009, mapOf("fn" to "n"))!!.capabilities)

    // Regression: NsdManager routinely reports an empty TXT map, and no real receiver advertises
    // ca=0. Treating "unknown" as audio-only would launch 8E6C866D on a TV and mirror nothing,
    // so an unknown device must be assumed to have a display.
    val unknown = CastDevice.from("x", "1.2.3.4", 8009, mapOf("fn" to "Living Room"))!!
    T.check("capabilities are reported as unknown", unknown.capabilitiesUnknown())
    T.check("an unknown device is assumed to have video output", unknown.hasVideoOutput())
    T.check("an unknown device is assumed to have audio output", unknown.hasAudioOutput())
    T.check("an unknown device is never treated as audio-only", !unknown.isAudioOnly())
    T.check("a real audio-only device is still audio-only", !audioOnly.capabilitiesUnknown())

    T.section("mDNS records arriving in separate packets")

    // Responders are not obliged to pack everything into one datagram.
    val split = MdnsResolver()
    split.acceptPacket(buildCompressedResponse())
    T.assertEquals("nothing resolves without TXT and A", 0, split.devices().size)
    split.acceptPacket(response)
    T.assertEquals("the device resolves once the rest arrives", 1, split.devices().size)

    T.section("mDNS name compression")

    val compressed = MdnsCodec.parse(buildCompressedResponse())
    T.assertEquals("a compressed owner name is decompressed", INSTANCE, compressed.additionals[0].name)
    T.assertEquals(
        "the record after a pointer still parses",
        8009,
        (compressed.additionals[0].parsed as DnsRecordData.Srv).port,
    )

    T.section("mDNS malformed input")

    T.assertThrows("a truncated header is rejected", "shorter than") { MdnsCodec.parse(ByteArray(4)) }
    T.assertThrows("a truncated record is rejected") {
        MdnsCodec.parse(response.copyOf(response.size - 3), response.size - 3)
    }
    // A pointer that points forwards would let a hostile packet loop the parser forever.
    val loop = ByteWriter(32).also {
        it.u16(0); it.u16(0x8400); it.u16(0); it.u16(1); it.u16(0); it.u16(0)
        it.u16(0xC00C)   // a pointer to itself
        it.u16(DnsType.PTR); it.u16(1); it.u32(0L); it.u16(0)
    }.toByteArray()
    T.assertThrows("a self-referential compression pointer is rejected") { MdnsCodec.parse(loop) }
    T.check("garbage is ignored rather than throwing from the resolver", run {
        MdnsResolver().acceptPacket(ByteArray(5) { 0xFF.toByte() }); true
    })
}
