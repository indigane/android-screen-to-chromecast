package app.castmirror.core.cast.streaming.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cast Streaming payload encryption: **AES-128-CTR, one keystream per frame**.
 *
 * This is a direct port of `openscreen:cast/streaming/impl/frame_crypto.cc`.
 *
 * ### The detail that trips up every implementation
 *
 * Encryption is per **frame**, not per packet. The entire encoded access unit is encrypted as one
 * contiguous CTR stream, and only *then* is the ciphertext chopped into RTP payload chunks. The
 * keystream therefore continues across packet boundaries: packet 1's first plaintext byte is
 * XORed with keystream byte 1449, not keystream byte 0. Never encrypt a packet payload
 * independently.
 *
 * ### IV derivation
 *
 * ```
 * iv[0..15] = 0
 * iv[8]  = (frameId >>> 24) and 0xFF
 * iv[9]  = (frameId >>> 16) and 0xFF
 * iv[10] = (frameId >>>  8) and 0xFF
 * iv[11] = (frameId       ) and 0xFF
 * for i in 0..15: iv[i] = iv[i] xor ivMask[i]
 * ```
 *
 * `frameId` is the **full 32-bit logical frame id**, not the 8-bit value that goes on the wire.
 * Bytes 0-7 and 12-15 are the mask unchanged; note that bytes 12-15 form CTR's counter region and
 * therefore start at `ivMask[12..15]` rather than zero — unusual but harmless, since CTR simply
 * increments the whole 128-bit block.
 *
 * CTR is a stream cipher: ciphertext length equals plaintext length. There is no padding, no MAC
 * and no authentication tag — Cast Streaming provides confidentiality only.
 *
 * @param aesKey the 16-byte `aesKey` sent in the OFFER for this stream.
 * @param aesIvMask the 16-byte `aesIvMask` sent in the OFFER for this stream.
 */
class FrameCrypto(aesKey: ByteArray, aesIvMask: ByteArray) {

    init {
        require(aesKey.size == 16) { "aesKey must be 16 bytes, was ${aesKey.size}" }
        require(aesIvMask.size == 16) { "aesIvMask must be 16 bytes, was ${aesIvMask.size}" }
    }

    private val keySpec = SecretKeySpec(aesKey.copyOf(), "AES")
    private val ivMask = aesIvMask.copyOf()
    private val cipher = Cipher.getInstance(TRANSFORM)

    /**
     * Builds the 16-byte AES-CTR counter block for [frameId].
     *
     * @param frameId the full 32-bit logical frame id. Must not be zero-as-null; libcast
     *   `CHECK`s `!frame_id.is_null()`.
     */
    fun ivFor(frameId: Int): ByteArray {
        val iv = ByteArray(16)
        iv[8] = ((frameId ushr 24) and 0xFF).toByte()
        iv[9] = ((frameId ushr 16) and 0xFF).toByte()
        iv[10] = ((frameId ushr 8) and 0xFF).toByte()
        iv[11] = (frameId and 0xFF).toByte()
        for (i in 0 until 16) iv[i] = (iv[i].toInt() xor ivMask[i].toInt()).toByte()
        return iv
    }

    /**
     * Encrypts a whole access unit.
     *
     * @param frameId the full 32-bit logical frame id.
     * @param plaintext the complete encoded frame.
     * @return ciphertext of exactly the same length.
     */
    fun encrypt(frameId: Int, plaintext: ByteArray): ByteArray = crypt(frameId, plaintext)

    /**
     * Decrypts a reassembled frame. CTR is symmetric, so this is the same operation as
     * [encrypt]; it exists for tests and for a future receiver implementation.
     */
    fun decrypt(frameId: Int, ciphertext: ByteArray): ByteArray = crypt(frameId, ciphertext)

    /**
     * Decrypts a frame that arrived as separate packet payloads, maintaining keystream position
     * across the chunk boundaries exactly as libcast's chunked `Crypt` path does.
     */
    fun decryptChunks(frameId: Int, chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val joined = ByteArray(total)
        var off = 0
        for (c in chunks) { System.arraycopy(c, 0, joined, off, c.size); off += c.size }
        return crypt(frameId, joined)
    }

    private fun crypt(frameId: Int, data: ByteArray): ByteArray = synchronized(cipher) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivFor(frameId)))
        cipher.doFinal(data)
    }

    companion object {
        /** The JCE transform Cast Streaming uses. */
        const val TRANSFORM = "AES/CTR/NoPadding"
    }
}
