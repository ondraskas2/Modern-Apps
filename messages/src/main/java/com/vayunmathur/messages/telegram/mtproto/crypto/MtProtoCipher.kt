package com.vayunmathur.messages.telegram.mtproto.crypto

import com.vayunmathur.messages.telegram.mtproto.tl.Int128
import com.vayunmathur.messages.telegram.mtproto.tl.Int256
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

data class DecryptedMessage(
    val salt: Long,
    val sessionId: Long,
    val messageId: Long,
    val seqNo: Int,
    val data: ByteArray,
)

object MtProtoCipher {
    private val random = SecureRandom()

    fun encrypt(authKey: ByteArray, salt: Long, sessionId: Long, msgId: Long, seqNo: Int, payload: ByteArray): ByteArray {
        val buf = TlBuffer()
        buf.putInt64(salt)
        buf.putInt64(sessionId)
        buf.putInt64(msgId)
        buf.putInt32(seqNo)
        buf.putInt32(payload.size)
        buf.putRawBytes(payload)

        val plaintext = buf.raw
        val paddingLen = 12 + (16 - ((plaintext.size + 12) % 16)) % 16
        val padding = ByteArray(paddingLen)
        random.nextBytes(padding)
        val paddedPlaintext = plaintext + padding

        val msgKey = messageKey(authKey, paddedPlaintext, Side.CLIENT)
        val (aesKey, aesIv) = keys(authKey, msgKey, Side.CLIENT)
        val encrypted = AesIge.encrypt(paddedPlaintext, aesKey, aesIv)

        val authKeyId = authKeyId(authKey)
        return authKeyId + msgKey.data + encrypted
    }

    fun decrypt(authKey: ByteArray, data: ByteArray): DecryptedMessage {
        val authKeyId = data.copyOfRange(0, 8)
        val expectedKeyId = authKeyId(authKey)
        require(authKeyId.contentEquals(expectedKeyId)) { "Auth key ID mismatch" }

        val msgKey = Int128(data.copyOfRange(8, 24))
        val encryptedData = data.copyOfRange(24, data.size)
        require(encryptedData.size % 16 == 0) { "Invalid encrypted data padding" }

        val (aesKey, aesIv) = keys(authKey, msgKey, Side.SERVER)
        val plaintext = AesIge.decrypt(encryptedData, aesKey, aesIv)

        val computedMsgKey = messageKey(authKey, plaintext, Side.SERVER)
        require(msgKey.data.contentEquals(computedMsgKey.data)) { "Message key verification failed" }

        val buf = TlBuffer(plaintext)
        val salt = buf.int64()
        val sessionId = buf.int64()
        val messageId = buf.int64()
        val seqNo = buf.int32()
        val dataLen = buf.int32()
        val msgData = buf.rawBytes(dataLen)

        return DecryptedMessage(salt, sessionId, messageId, seqNo, msgData)
    }

    fun authKeyId(authKey: ByteArray): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(authKey)
        return sha1.copyOfRange(12, 20)
    }

    private fun messageKey(authKey: ByteArray, paddedPlaintext: ByteArray, side: Side): Int128 {
        val x = side.x
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(authKey, 88 + x, 32)
        sha.update(paddedPlaintext)
        val hash = sha.digest()
        return Int128(hash.copyOfRange(8, 24))
    }

    private fun keys(authKey: ByteArray, msgKey: Int128, side: Side): Pair<ByteArray, ByteArray> {
        val x = side.x

        val sha256a = run {
            val h = MessageDigest.getInstance("SHA-256")
            h.update(msgKey.data)
            h.update(authKey, x, 36)
            h.digest()
        }
        val sha256b = run {
            val h = MessageDigest.getInstance("SHA-256")
            h.update(authKey, 40 + x, 36)
            h.update(msgKey.data)
            h.digest()
        }

        val aesKey = ByteArray(32)
        System.arraycopy(sha256a, 0, aesKey, 0, 8)
        System.arraycopy(sha256b, 8, aesKey, 8, 16)
        System.arraycopy(sha256a, 24, aesKey, 24, 8)

        val aesIv = ByteArray(32)
        System.arraycopy(sha256b, 0, aesIv, 0, 8)
        System.arraycopy(sha256a, 8, aesIv, 8, 16)
        System.arraycopy(sha256b, 24, aesIv, 24, 8)

        return aesKey to aesIv
    }

    enum class Side(val x: Int) {
        CLIENT(0),
        SERVER(8)
    }
}
