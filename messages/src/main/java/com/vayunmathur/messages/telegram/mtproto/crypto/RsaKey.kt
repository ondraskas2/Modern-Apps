package com.vayunmathur.messages.telegram.mtproto.crypto

import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

data class RsaPublicKeyEntry(
    val fingerprint: Long,
    val key: RSAPublicKey,
)

object RsaKey {
    private val random = SecureRandom()
    private const val TAG = "RsaKey"

    private val keys: List<RsaPublicKeyEntry> by lazy {
        TELEGRAM_PEM_KEYS.mapNotNull { pem ->
            try {
                val der = Base64.decode(pem, Base64.DEFAULT)
                val (mod, exp) = parsePkcs1Der(der)
                val modulus = BigInteger(1, mod)
                val exponent = BigInteger(1, exp)
                val spec = RSAPublicKeySpec(modulus, exponent)
                val key = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
                val fingerprint = computeFingerprint(mod, exp)
                Log.d(TAG, "Loaded key fp: $fingerprint (0x${fingerprint.toULong().toString(16)})")
                RsaPublicKeyEntry(fingerprint, key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse RSA key", e)
                null
            }
        }
    }

    fun selectKey(fingerprints: List<Long>): RsaPublicKeyEntry? {
        Log.d(TAG, "Server fps: ${fingerprints.map { "0x${it.toULong().toString(16)}" }}")
        Log.d(TAG, "Our fps: ${keys.map { "0x${it.fingerprint.toULong().toString(16)}" }}")
        for (fp in fingerprints) {
            val entry = keys.find { it.fingerprint == fp }
            if (entry != null) return entry
        }
        return null
    }

    fun rsaPad(data: ByteArray, key: RSAPublicKey): ByteArray {
        require(data.size <= 144) { "Data too long for RSA_PAD: ${data.size}" }

        val dataWithPadding = ByteArray(192)
        System.arraycopy(data, 0, dataWithPadding, 0, data.size)
        random.nextBytes(ByteArray(192 - data.size).also {
            System.arraycopy(it, 0, dataWithPadding, data.size, it.size)
        })

        val dataPadReversed = dataWithPadding.copyOf()
        dataPadReversed.reverse()

        while (true) {
            val tempKey = ByteArray(32)
            random.nextBytes(tempKey)

            val sha = MessageDigest.getInstance("SHA-256")
            sha.update(tempKey)
            sha.update(dataWithPadding)
            val hash = sha.digest()

            val dataWithHash = dataPadReversed + hash
            val aesEncrypted = AesIge.encrypt(dataWithHash, tempKey, ByteArray(32))

            val aesEncryptedHash = MessageDigest.getInstance("SHA-256").digest(aesEncrypted)
            val tempKeyXor = ByteArray(32)
            for (i in 0 until 32) {
                tempKeyXor[i] = (tempKey[i].toInt() xor aesEncryptedHash[i].toInt()).toByte()
            }

            val keyAesEncrypted = tempKeyXor + aesEncrypted
            val keyBig = BigInteger(1, keyAesEncrypted)
            if (keyBig >= key.modulus) continue

            val encrypted = keyBig.modPow(key.publicExponent, key.modulus)
            val result = ByteArray(256)
            val encBytes = encrypted.toByteArray()
            val srcPos = if (encBytes.size > 256) encBytes.size - 256 else 0
            val dstPos = if (encBytes.size < 256) 256 - encBytes.size else 0
            System.arraycopy(encBytes, srcPos, result, dstPos, minOf(encBytes.size, 256))
            return result
        }
    }

    private fun computeFingerprint(modBytes: ByteArray, expBytes: ByteArray): Long {
        val buf = com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer()
        buf.putBytes(modBytes)
        buf.putBytes(expBytes)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(buf.raw)
        var fp = 0L
        for (i in 0 until 8) {
            fp = fp or ((sha1[sha1.size - 8 + i].toLong() and 0xFF) shl (i * 8))
        }
        return fp
    }

    private fun parsePkcs1Der(der: ByteArray): Pair<ByteArray, ByteArray> {
        var i = 0
        check(der[i] == 0x30.toByte()) { "Expected SEQUENCE" }
        i++
        i = skipDerLength(der, i)

        // modulus INTEGER
        check(der[i] == 0x02.toByte()) { "Expected INTEGER for modulus" }
        i++
        val (modLen, modStart) = readDerLength(der, i)
        i = modStart
        var mod = der.copyOfRange(i, i + modLen)
        if (mod[0] == 0.toByte()) mod = mod.copyOfRange(1, mod.size)
        i += modLen

        // exponent INTEGER
        check(der[i] == 0x02.toByte()) { "Expected INTEGER for exponent" }
        i++
        val (expLen, expStart) = readDerLength(der, i)
        i = expStart
        var exp = der.copyOfRange(i, i + expLen)
        if (exp[0] == 0.toByte()) exp = exp.copyOfRange(1, exp.size)

        return mod to exp
    }

    private fun skipDerLength(der: ByteArray, pos: Int): Int {
        val first = der[pos].toInt() and 0xFF
        return if (first and 0x80 != 0) {
            val numBytes = first and 0x7F
            pos + 1 + numBytes
        } else {
            pos + 1
        }
    }

    private fun readDerLength(der: ByteArray, pos: Int): Pair<Int, Int> {
        val first = der[pos].toInt() and 0xFF
        return if (first and 0x80 != 0) {
            val numBytes = first and 0x7F
            var length = 0
            for (j in 1..numBytes) {
                length = (length shl 8) or (der[pos + j].toInt() and 0xFF)
            }
            length to (pos + 1 + numBytes)
        } else {
            first to (pos + 1)
        }
    }

    // Base64-encoded PKCS#1 DER from gotd/mtproto/_data/public_keys.pem
    private val TELEGRAM_PEM_KEYS = listOf(
        // Key 1
        "MIIBCgKCAQEAyMEdY1aR+sCR3ZSJrtztKTKqigvO/vBfqACJLZtS7QMgCGXJ6XIR" +
        "yy7mx66W0/sOFa7/1mAZtEoIokDP3ShoqF4fVNb6XeqgQfaUHd8wJpDWHcR2OFwv" +
        "plUUI1PLTktZ9uW2WE23b+ixNwJjJGwBDJPQEQFBE+vfmH0JP503wr5INS1poWg/" +
        "j25sIWeYPHYeOrFp/eXaqhISP6G+q2IeTaWTXpwZj4LzXq5YOpk4bYEQ6mvRq7D1" +
        "aHWfYmlEGepfaYR8Q0YqvvhYtMte3ITnuSJs171+GDqpdKcSwHnd6FudwGO4pcCO" +
        "j4WcDuXc2CTHgH8gFTNhp/Y8/SpDOhvn9QIDAQAB",
        // Key 2
        "MIIBCgKCAQEA6LszBcC1LGzyr992NzE0ieY+BSaOW622Aa9Bd4ZHLl+TuFQ4lo4g" +
        "5nKaMBwK/BIb9xUfg0Q29/2mgIR6Zr9krM7HjuIcCzFvDtr+L0GQjae9H0pRB2O" +
        "O62cECs5HKhT5DZ98K33vmWiLowc621dQuwKWSQKjWf50XYFw42h21P2KXUGyp2y/" +
        "+aEyZ+uVgLLQbRA1dEjSDZ2iGRy12Mk5gpYc397aYp438fsJoHIgJ2lgMv5h7WY9" +
        "t6N/byY9Nw9p21Og3AoXSL2q/2IJ1WRUhebgAdGVMlV1fkuOQoEzR7EdpqtQD9Cs" +
        "5+bfo3Nhmcyvk5ftB0WkJ9z6bNZ7yxrP8wIDAQAB",
    )
}
