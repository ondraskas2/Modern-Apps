package com.vayunmathur.messages.telegram.mtproto.transport

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MTProto "obfuscated2" transport codec used by MTProxy and as TLS-like stream obfuscation.
 *
 * The client emits a 64-byte init packet; key/iv material for two independent AES-256-CTR streams
 * (client->server and server->client) is derived from it. When an MTProxy secret is present it is
 * mixed into both keys via SHA-256. After the handshake every byte on the wire is CTR-encrypted, so
 * the underlying frames (here the intermediate length-prefixed codec) ride inside the cipher stream.
 *
 * Ref: https://core.telegram.org/mtproto/mtproto-transports#transport-obfuscation and the MTProxy
 * fake-TLS / secret handling in the Go telegram libraries.
 *
 * // UNVERIFIED runtime — no proxy endpoint available in-tree to exercise the handshake against.
 */
class ObfuscatedCodec private constructor(
    private val encryptCipher: Cipher,
    private val decryptCipher: Cipher,
    /** The 64-byte init packet to send to the server before any framed data. */
    val initPacket: ByteArray,
) {
    fun encrypt(data: ByteArray): ByteArray = encryptCipher.update(data)

    fun decrypt(data: ByteArray): ByteArray = decryptCipher.update(data)

    companion object {
        // Intermediate transport tag (little-endian 0xeeeeeeee), repeated across init[56..60].
        private val INTERMEDIATE_TAG = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())

        // 4-byte prefixes that must not appear at init[0..4] (they collide with HTTP / known tags).
        private val FORBIDDEN_PREFIXES = setOf(
            0x44414548, // HEAD
            0x54534f50, // POST
            0x20544547, // GET
            0x4954504f, // OPTI
            0xeeeeeeee.toInt(),
            0xdddddddd.toInt(),
            0x02010316,
        )

        /**
         * Builds an obfuscated codec.
         *
         * @param dcId optional DC id encoded into init[60..62] (little-endian); 0 to omit.
         * @param secret optional MTProxy secret (16 raw bytes, or the dd-/ee-prefixed variants with
         *               the first byte stripped by the caller) mixed into both stream keys.
         */
        fun create(dcId: Int = 0, secret: ByteArray? = null): ObfuscatedCodec {
            val random = SecureRandom()
            val init = ByteArray(64)
            while (true) {
                random.nextBytes(init)
                if (init[0] == 0xef.toByte()) continue
                val firstWord = beInt(init, 0)
                if (firstWord in FORBIDDEN_PREFIXES) continue
                if (beInt(init, 4) == 0) continue
                break
            }
            // Transport tag + optional DC id.
            System.arraycopy(INTERMEDIATE_TAG, 0, init, 56, 4)
            if (dcId != 0) {
                init[60] = (dcId and 0xff).toByte()
                init[61] = ((dcId shr 8) and 0xff).toByte()
            }

            var encKey = init.copyOfRange(8, 40)
            val encIv = init.copyOfRange(40, 56)

            val reversed = init.copyOfRange(8, 56).reversedArray()
            var decKey = reversed.copyOfRange(0, 32)
            val decIv = reversed.copyOfRange(32, 48)

            if (secret != null && secret.isNotEmpty()) {
                encKey = sha256(encKey + secret)
                decKey = sha256(decKey + secret)
            }

            val encryptCipher = ctrCipher(encKey, encIv)
            val decryptCipher = ctrCipher(decKey, decIv)

            // The init packet is sent in the clear except for its last 8 bytes, which are replaced by
            // the CTR-encrypted version of the same bytes (advancing the encrypt stream over all 64).
            val encryptedInit = encryptCipher.update(init)
            val packet = init.copyOf(64)
            System.arraycopy(encryptedInit, 56, packet, 56, 8)
            return ObfuscatedCodec(encryptCipher, decryptCipher, packet)
        }

        private fun ctrCipher(key: ByteArray, iv: ByteArray): Cipher {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher
        }

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun beInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xff) shl 24) or
                ((b[off + 1].toInt() and 0xff) shl 16) or
                ((b[off + 2].toInt() and 0xff) shl 8) or
                (b[off + 3].toInt() and 0xff)
    }
}
