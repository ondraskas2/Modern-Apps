package com.vayunmathur.messages.telegram.mtproto.crypto

import android.util.Log
import com.vayunmathur.messages.telegram.mtproto.proto.MessageFraming
import com.vayunmathur.messages.telegram.mtproto.proto.MessageId
import com.vayunmathur.messages.telegram.mtproto.tl.Int128
import com.vayunmathur.messages.telegram.mtproto.tl.Int256
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.transport.TcpTransport
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

data class AuthResult(
    val authKey: ByteArray,
    val authKeyId: ByteArray,
    val serverSalt: Long,
    val sessionId: Long,
)

class KeyExchange(private val transport: TcpTransport, private val dc: Int = 2) {
    private val random = SecureRandom()
    private val TAG = "KeyExchange"

    suspend fun perform(): AuthResult {
        // Step 1: req_pq_multi
        val nonce = randomInt128()
        val reqPq = TlBuffer()
        reqPq.putId(0x60469778.toInt()) // req_pq_multi
        reqPq.putInt128(nonce)
        sendUnencrypted(reqPq.raw)

        // Step 2: receive resPQ
        val resPqData = receiveUnencrypted()
        val resPqBuf = TlBuffer(resPqData)
        val resPqId = resPqBuf.int32()
        check(resPqId == 0x05162463.toInt()) { "Expected resPQ, got 0x${resPqId.toUInt().toString(16)}" }
        val resNonce = resPqBuf.int128()
        check(resNonce.data.contentEquals(nonce.data)) { "ResPQ nonce mismatch" }
        val resSrvNonce = resPqBuf.int128()
        val pq = resPqBuf.bytes()
        val vecId = resPqBuf.int32() // TYPE_VECTOR
        val fpLen = resPqBuf.int32()
        val fingerprints = (0 until fpLen).map { resPqBuf.int64() }

        Log.d(TAG, "Received resPQ, ${fingerprints.size} fingerprints")

        // Step 3: Factor pq
        val pqBig = BigInteger(1, pq)
        val (p, q) = PqMath.decompose(pqBig)
        val pBytes = p.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val qBytes = q.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

        // Step 4: Select RSA key
        val rsaEntry = RsaKey.selectKey(fingerprints)
            ?: throw IllegalStateException("No matching RSA key found")

        // Generate new_nonce
        val newNonce = randomInt256()

        // Build p_q_inner_data_dc
        val innerData = TlBuffer()
        innerData.putId(0xa9f55f95.toInt()) // p_q_inner_data_dc
        innerData.putBytes(pq)
        innerData.putBytes(pBytes)
        innerData.putBytes(qBytes)
        innerData.putInt128(nonce)
        innerData.putInt128(resSrvNonce)
        innerData.putInt256(newNonce)
        innerData.putInt32(dc)

        // RSA_PAD encrypt
        val encryptedData = RsaKey.rsaPad(innerData.raw, rsaEntry.key)

        // Step 5: req_DH_params
        val reqDH = TlBuffer()
        reqDH.putId(0xd712e4be.toInt()) // req_DH_params
        reqDH.putInt128(nonce)
        reqDH.putInt128(resSrvNonce)
        reqDH.putBytes(pBytes)
        reqDH.putBytes(qBytes)
        reqDH.putInt64(rsaEntry.fingerprint)
        reqDH.putBytes(encryptedData)
        sendUnencrypted(reqDH.raw)

        // Step 6: Receive server_DH_params_ok
        val dhParamsData = receiveUnencrypted()
        val dhBuf = TlBuffer(dhParamsData)
        val dhId = dhBuf.int32()
        check(dhId == 0xd0e8075c.toInt()) { "Expected server_DH_params_ok, got 0x${dhId.toUInt().toString(16)}" }
        val dhNonce = dhBuf.int128()
        check(dhNonce.data.contentEquals(nonce.data)) { "DH params nonce mismatch" }
        val dhSrvNonce = dhBuf.int128()
        check(dhSrvNonce.data.contentEquals(resSrvNonce.data)) { "DH params server_nonce mismatch" }
        val encryptedAnswer = dhBuf.bytes()

        // Decrypt with temp AES keys derived from nonces
        val (tmpKey, tmpIv) = tempAesKeys(newNonce, resSrvNonce)
        val answerWithHash = AesIge.decrypt(encryptedAnswer, tmpKey, tmpIv)

        // GuessDataWithHash: SHA1(data) + data + padding
        val sha1Size = 20
        val answerData = guessDataWithHash(answerWithHash) ?: throw IllegalStateException("Failed to decode DH inner data")

        val innerBuf = TlBuffer(answerData)
        val innerDhId = innerBuf.int32()
        check(innerDhId == 0xb5890dba.toInt()) { "Expected server_DH_inner_data" }
        val innerNonce = innerBuf.int128()
        val innerSrvNonce = innerBuf.int128()
        val gValue = innerBuf.int32()
        val dhPrimeBytes = innerBuf.bytes()
        val gABytes = innerBuf.bytes()
        val serverTime = innerBuf.int32()

        val dhPrime = BigInteger(1, dhPrimeBytes)
        val g = BigInteger.valueOf(gValue.toLong())
        val gA = BigInteger(1, gABytes)

        // Step 7: Generate b, compute g_b
        val bBytes = ByteArray(256)
        random.nextBytes(bBytes)
        val b = BigInteger(1, bBytes)
        val gB = g.modPow(b, dhPrime)

        // Build client_DH_inner_data
        val clientInner = TlBuffer()
        clientInner.putId(0x6643b654.toInt()) // client_DH_inner_data
        clientInner.putInt128(nonce)
        clientInner.putInt128(resSrvNonce)
        clientInner.putInt64(0L) // retry_id = 0
        val gBbytes = gB.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        clientInner.putBytes(gBbytes)

        // Encrypt with temp keys
        val clientEncrypted = encryptExchangeAnswer(clientInner.raw, tmpKey, tmpIv)

        // Step 8: set_client_DH_params
        val setParams = TlBuffer()
        setParams.putId(0xf5045f1f.toInt()) // set_client_DH_params
        setParams.putInt128(nonce)
        setParams.putInt128(resSrvNonce)
        setParams.putBytes(clientEncrypted)
        sendUnencrypted(setParams.raw)

        // Receive dh_gen_ok
        val dhGenData = receiveUnencrypted()
        val dhGenBuf = TlBuffer(dhGenData)
        val dhGenId = dhGenBuf.int32()
        check(dhGenId == 0x3bcbf734.toInt()) { "Expected dh_gen_ok, got 0x${dhGenId.toUInt().toString(16)}" }

        // Compute auth_key = g_a ^ b mod dhPrime
        val authKeyBig = gA.modPow(b, dhPrime)
        val authKey = ByteArray(256)
        val akBytes = authKeyBig.toByteArray()
        val srcPos = if (akBytes.size > 256) akBytes.size - 256 else 0
        val dstPos = if (akBytes.size < 256) 256 - akBytes.size else 0
        System.arraycopy(akBytes, srcPos, authKey, dstPos, minOf(akBytes.size, 256))

        val authKeyId = MtProtoCipher.authKeyId(authKey)

        // Compute server salt = new_nonce[0:8] XOR server_nonce[0:8]
        val saltBytes = ByteArray(8)
        for (i in 0 until 8) {
            saltBytes[i] = (newNonce.data[i].toInt() xor resSrvNonce.data[i].toInt()).toByte()
        }
        val serverSalt = ByteBuffer.wrap(saltBytes).order(ByteOrder.LITTLE_ENDIAN).long

        // Generate session ID
        val sessionIdBytes = ByteArray(8)
        random.nextBytes(sessionIdBytes)
        val sessionId = ByteBuffer.wrap(sessionIdBytes).order(ByteOrder.LITTLE_ENDIAN).long

        Log.i(TAG, "Key exchange complete")
        return AuthResult(authKey, authKeyId, serverSalt, sessionId)
    }

    private suspend fun sendUnencrypted(data: ByteArray) {
        val msg = MessageFraming.writeUnencrypted(MessageId.generate(), data)
        transport.send(msg)
    }

    private suspend fun receiveUnencrypted(): ByteArray {
        val raw = transport.receive()
        return MessageFraming.readUnencrypted(raw)
    }

    private fun randomInt128(): Int128 {
        val b = ByteArray(16)
        random.nextBytes(b)
        return Int128(b)
    }

    private fun randomInt256(): Int256 {
        val b = ByteArray(32)
        random.nextBytes(b)
        return Int256(b)
    }

    private fun tempAesKeys(newNonce: Int256, serverNonce: Int128): Pair<ByteArray, ByteArray> {
        fun sha1(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            for (p in parts) md.update(p)
            return md.digest()
        }

        val nnBytes = newNonce.data
        val snBytes = serverNonce.data

        // tmp_aes_key = SHA1(new_nonce + server_nonce) + substr(SHA1(server_nonce + new_nonce), 0, 12)
        val key = sha1(nnBytes, snBytes) + sha1(snBytes, nnBytes).copyOfRange(0, 12)

        // tmp_aes_iv = substr(SHA1(server_nonce + new_nonce), 12, 8) + SHA1(new_nonce + new_nonce) + substr(new_nonce, 0, 4)
        val iv = sha1(snBytes, nnBytes).copyOfRange(12, 20) + sha1(nnBytes, nnBytes) + nnBytes.copyOfRange(0, 4)

        return key to iv
    }

    private fun guessDataWithHash(dataWithHash: ByteArray): ByteArray? {
        val sha1Size = 20
        if (dataWithHash.size <= sha1Size) return null
        val expectedHash = dataWithHash.copyOfRange(0, sha1Size)
        for (i in 0 until 16) {
            if (dataWithHash.size - i < sha1Size) return null
            val data = dataWithHash.copyOfRange(sha1Size, dataWithHash.size - i)
            val hash = MessageDigest.getInstance("SHA-1").digest(data)
            if (hash.contentEquals(expectedHash)) return data
        }
        return null
    }

    private fun encryptExchangeAnswer(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val sha1Hash = MessageDigest.getInstance("SHA-1").digest(data)
        val totalLen = sha1Hash.size + data.size
        val paddedLen = if (totalLen % 16 == 0) totalLen else totalLen + (16 - totalLen % 16)
        val dataWithHash = ByteArray(paddedLen)
        System.arraycopy(sha1Hash, 0, dataWithHash, 0, sha1Hash.size)
        System.arraycopy(data, 0, dataWithHash, sha1Hash.size, data.size)
        if (paddedLen > totalLen) {
            val padding = ByteArray(paddedLen - totalLen)
            random.nextBytes(padding)
            System.arraycopy(padding, 0, dataWithHash, totalLen, padding.size)
        }
        return AesIge.encrypt(dataWithHash, key, iv)
    }
}
