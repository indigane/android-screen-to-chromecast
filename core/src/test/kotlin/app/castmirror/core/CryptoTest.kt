package app.castmirror.core

import app.castmirror.core.cast.streaming.EncodedFrame
import app.castmirror.core.cast.streaming.EncryptedFrame
import app.castmirror.core.cast.streaming.crypto.FrameCrypto
import app.castmirror.core.cast.streaming.rtp.RtpPacketizer
import app.castmirror.core.util.Bytes

/** AES-128-CTR frame encryption: IV derivation and keystream continuity. */
fun runCryptoTests() {
    T.section("FrameCrypto IV derivation")

    val key = Bytes.fromHex("000102030405060708090a0b0c0d0e0f")
    val mask = Bytes.fromHex("000102030405060708090a0b0c0d0e0f")
    val crypto = FrameCrypto(key, mask)

    // Hand-computed for frame_id 0x01020304:
    //   iv = 00*8 || 01 02 03 04 || 00*4          (frame id big-endian at bytes 8..11)
    //   then XOR the whole 16 bytes with the mask:
    //     bytes 0-7  : 00 01 02 03 04 05 06 07   (mask unchanged)
    //     byte  8    : 01 ^ 08 = 09
    //     byte  9    : 02 ^ 09 = 0b
    //     byte 10    : 03 ^ 0a = 09
    //     byte 11    : 04 ^ 0b = 0f
    //     bytes 12-15: 0c 0d 0e 0f               (mask unchanged; this is CTR's counter region)
    val expectedIv = Bytes.fromHex("0001020304050607090b090f0c0d0e0f")
    T.assertBytes("IV for frame id 0x01020304", expectedIv, crypto.ivFor(0x01020304))

    // A zero mask leaves the frame id visible at bytes 8..11 and everything else zero.
    val zeroMaskCrypto = FrameCrypto(key, ByteArray(16))
    T.assertBytes(
        "IV with a zero mask is just the frame id",
        Bytes.fromHex("00000000000000000102030400000000"),
        zeroMaskCrypto.ivFor(0x01020304),
    )
    T.assertBytes(
        "IV for frame id 1 with a zero mask",
        Bytes.fromHex("00000000000000000000000100000000"),
        zeroMaskCrypto.ivFor(1),
    )
    T.assertBytes("IV for frame id 0 is the mask unchanged", mask, crypto.ivFor(0))

    // Only the low 32 bits of the frame id are used, and they are written big-endian.
    T.assertBytes(
        "the frame id is written big-endian",
        Bytes.fromHex("0000000000000000deadbeef00000000"),
        zeroMaskCrypto.ivFor(0xDEADBEEF.toInt()),
    )

    T.section("FrameCrypto round-trip")

    val plaintext = ByteArray(5000) { ((it * 31) and 0xFF).toByte() }
    val ciphertext = crypto.encrypt(0x01020304, plaintext)
    T.assertEquals("CTR preserves length", plaintext.size, ciphertext.size)
    T.check("ciphertext differs from plaintext", !ciphertext.contentEquals(plaintext))
    T.assertBytes("decrypt(encrypt(x)) == x", plaintext, crypto.decrypt(0x01020304, ciphertext))

    T.check(
        "a different frame id yields a different keystream",
        !crypto.encrypt(0x01020305, plaintext).contentEquals(ciphertext),
    )
    val otherKey = FrameCrypto(Bytes.fromHex("ffeeddccbbaa99887766554433221100"), mask)
    T.check(
        "a different key yields a different keystream",
        !otherKey.encrypt(0x01020304, plaintext).contentEquals(ciphertext),
    )
    T.assertBytes(
        "encryption is deterministic for a given (key, mask, frame id)",
        ciphertext,
        FrameCrypto(key, mask).encrypt(0x01020304, plaintext),
    )
    T.assertBytes("an empty frame encrypts to nothing", ByteArray(0), crypto.encrypt(1, ByteArray(0)))

    T.section("FrameCrypto keystream continuity across packet boundaries")

    // Encryption is per FRAME: the whole access unit is one CTR stream and only then is the
    // ciphertext chopped into RTP payload chunks. Reassembling the packet payloads and decrypting
    // as one blob must return the original plaintext.
    val frameId = 0x00ABCDEF
    val whole = crypto.encrypt(frameId, plaintext)
    val frame = EncodedFrame(
        frameId = frameId,
        isKeyFrame = true,
        referencedFrameId = frameId,
        rtpTimestamp = 12345,
        data = ByteArray(0),
    )
    val packetizer = RtpPacketizer(100, 62812L, initialSequenceNumber = 0)
    val encrypted = EncryptedFrame(frame, whole)
    val numPackets = packetizer.computeNumberOfPackets(encrypted)
    T.assertEquals("5000 bytes needs 4 packets at a 1449-byte chunk size", 4, numPackets)

    val chunks = ArrayList<ByteArray>()
    for (i in 0 until numPackets) {
        val p = packetizer.generatePacket(encrypted, i)
        chunks.add(p.copyOfRange(19, p.size))
    }
    T.assertEquals("reassembled length matches", plaintext.size, chunks.sumOf { it.size })
    T.assertBytes(
        "reassembled packets decrypt to the original frame",
        plaintext,
        crypto.decryptChunks(frameId, chunks),
    )

    // Encrypting each chunk independently is the classic bug, and it produces different bytes
    // for every chunk after the first: proof that the keystream really does continue.
    var offset = 0
    var differed = false
    for ((i, c) in chunks.withIndex()) {
        val wrong = crypto.encrypt(frameId, plaintext.copyOfRange(offset, offset + c.size))
        if (i > 0 && !wrong.contentEquals(c)) differed = true
        if (i == 0) T.assertBytes("packet 0 matches a from-scratch encryption", c, wrong)
        offset += c.size
    }
    T.check("packets after the first do NOT match per-packet encryption", differed)

    // The chunked decrypt path must agree with the one-shot path.
    T.assertBytes(
        "chunked decrypt equals one-shot decrypt",
        crypto.decrypt(frameId, whole),
        crypto.decryptChunks(frameId, chunks),
    )
    // Chunk boundaries that are not multiples of the AES block size are the interesting case.
    T.assertBytes(
        "keystream survives a 1-byte chunk split",
        plaintext,
        crypto.decryptChunks(frameId, listOf(whole.copyOfRange(0, 1), whole.copyOfRange(1, whole.size))),
    )
    T.assertBytes(
        "keystream survives a 17-byte chunk split",
        plaintext,
        crypto.decryptChunks(frameId, listOf(whole.copyOfRange(0, 17), whole.copyOfRange(17, whole.size))),
    )

    T.section("FrameCrypto validation")

    T.assertThrows("key must be 16 bytes", "aesKey") { FrameCrypto(ByteArray(15), ByteArray(16)) }
    T.assertThrows("IV mask must be 16 bytes", "aesIvMask") { FrameCrypto(ByteArray(16), ByteArray(17)) }
}
