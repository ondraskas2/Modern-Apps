package com.vayunmathur.messages.telegram.mtproto.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.params.KeyParameter

object AesIge {
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv.size == 32) { "IGE IV must be 32 bytes" }
        require(data.size % 16 == 0) { "Data must be aligned to 16 bytes" }

        val aes = AESEngine.newInstance()
        aes.init(true, KeyParameter(key))
        val blockSize = 16

        val ivP1 = iv.copyOfRange(0, 16)
        val ivP2 = iv.copyOfRange(16, 32)

        val result = ByteArray(data.size)
        val xorBuf = ByteArray(blockSize)
        val outBlock = ByteArray(blockSize)

        var prevCiphertext = ivP1
        var prevPlaintext = ivP2

        for (i in data.indices step blockSize) {
            for (j in 0 until blockSize) {
                xorBuf[j] = (data[i + j].toInt() xor prevCiphertext[j].toInt()).toByte()
            }
            aes.processBlock(xorBuf, 0, outBlock, 0)
            for (j in 0 until blockSize) {
                result[i + j] = (outBlock[j].toInt() xor prevPlaintext[j].toInt()).toByte()
            }
            prevCiphertext = result.copyOfRange(i, i + blockSize)
            prevPlaintext = data.copyOfRange(i, i + blockSize)
        }
        return result
    }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv.size == 32) { "IGE IV must be 32 bytes" }
        require(data.size % 16 == 0) { "Data must be aligned to 16 bytes" }

        val aes = AESEngine.newInstance()
        aes.init(false, KeyParameter(key))
        val blockSize = 16

        val ivP1 = iv.copyOfRange(0, 16)
        val ivP2 = iv.copyOfRange(16, 32)

        val result = ByteArray(data.size)
        val xorBuf = ByteArray(blockSize)
        val outBlock = ByteArray(blockSize)

        var prevCiphertext = ivP1
        var prevPlaintext = ivP2

        for (i in data.indices step blockSize) {
            for (j in 0 until blockSize) {
                xorBuf[j] = (data[i + j].toInt() xor prevPlaintext[j].toInt()).toByte()
            }
            aes.processBlock(xorBuf, 0, outBlock, 0)
            for (j in 0 until blockSize) {
                result[i + j] = (outBlock[j].toInt() xor prevCiphertext[j].toInt()).toByte()
            }
            prevPlaintext = result.copyOfRange(i, i + blockSize)
            prevCiphertext = data.copyOfRange(i, i + blockSize)
        }
        return result
    }
}
