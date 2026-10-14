package com.castbay.service

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 framing for a HomeKit-paired AirPlay channel, the Kotlin twin of hap.c:
 * frames of up to 1024 bytes, each [u16 LE length][ciphertext][16-byte tag], the length as AAD,
 * nonce = 4 zero bytes + 64-bit LE counter per direction. Keys are HKDF-SHA512 of the pairing
 * secret. Not thread safe; callers serialise reads and writes separately.
 */
class HapChannelCipher(secret: ByteArray, salt: String, readInfo: String, writeInfo: String) {

    private val readKey = hkdf(secret, salt, readInfo)
    private val writeKey = hkdf(secret, salt, writeInfo)
    private var readCount = 0L
    private var writeCount = 0L
    private var pending = ByteArray(0)

    fun encrypt(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plain.size + 32)
        var off = 0
        while (off < plain.size) {
            val n = minOf(FRAME_MAX, plain.size - off)
            val aad = byteArrayOf((n and 0xff).toByte(), (n shr 8).toByte())
            val c = _cipher(Cipher.ENCRYPT_MODE, writeKey, writeCount++)
            c.updateAAD(aad)
            out.write(aad)
            out.write(c.doFinal(plain, off, n))
            off += n
        }
        return out.toByteArray()
    }

    /** Whole frames decrypted so far; a partial frame is kept. Throws on authentication failure. */
    fun decrypt(input: ByteArray, len: Int): ByteArray {
        pending += input.copyOf(len)
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pending.size - pos >= 2) {
            val n = (pending[pos].toInt() and 0xff) or ((pending[pos + 1].toInt() and 0xff) shl 8)
            if (pending.size - pos < 2 + n + TAG_LEN) break
            val c = _cipher(Cipher.DECRYPT_MODE, readKey, readCount++)
            c.updateAAD(pending, pos, 2)
            out.write(c.doFinal(pending, pos + 2, n + TAG_LEN))
            pos += 2 + n + TAG_LEN
        }
        pending = pending.copyOfRange(pos, pending.size)
        return out.toByteArray()
    }

    private fun _cipher(mode: Int, key: ByteArray, count: Long): Cipher {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (count ushr (8 * i)).toByte()
        return Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        }
    }

    companion object {
        private const val FRAME_MAX = 1024
        private const val TAG_LEN = 16

        /**
         * Key for the sender's TLS-PSK media server: macOS derives it with salt "Pair-TLS-PSK",
         * no info, 16 bytes, from the pairing secret (AirPlaySender, read from the dyld cache).
         */
        fun mediaPsk(secret: ByteArray): ByteArray = hkdf(secret, "Pair-TLS-PSK", "", 16)

        // HKDF-SHA512 (RFC 5869), up to 64 bytes of output
        fun hkdf(secret: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
            val extract = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(salt.toByteArray(), "HmacSHA512")) }
            val prk = extract.doFinal(secret)
            val expand = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(prk, "HmacSHA512")) }
            expand.update(info.toByteArray())
            expand.update(1)
            return expand.doFinal().copyOf(length)
        }
    }
}
