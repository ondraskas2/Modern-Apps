package com.vayunmathur.messages.signal.auth

import com.vayunmathur.messages.signal.proto.ProvisioningProtos.ProvisionEnvelope
import com.vayunmathur.messages.signal.proto.ProvisioningProtos.ProvisionMessage
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProvisioningCipher {
    private var keyPair: ECKeyPair? = null

    fun getPublicKey(): ECPublicKey {
        if (keyPair == null) {
            keyPair = Curve.generateKeyPair()
        }
        return keyPair!!.publicKey
    }

    fun decrypt(envelope: ProvisionEnvelope): ProvisionMessage {
        val kp = keyPair ?: throw IllegalStateException("Key pair not initialized")
        val masterEphemeral = Curve.decodePoint(byteArrayOf(0x05) + envelope.publicKey.toByteArray(), 0)
        val body = envelope.body.toByteArray()
        require(body[0].toInt() == 1) { "Unsupported ProvisionMessage version: ${body[0]}" }

        val iv = body.copyOfRange(1, 17)
        val mac = body.copyOfRange(body.size - 32, body.size)
        val cipherText = body.copyOfRange(17, body.size - 32)
        val ivAndCipherText = body.copyOfRange(0, body.size - 32)

        val agreement = Curve.calculateAgreement(masterEphemeral, kp.privateKey)

        val sharedSecrets = hkdfDeriveSecrets(agreement, "TextSecure Provisioning Message".toByteArray(), 64)

        val cipherKey = sharedSecrets.copyOfRange(0, 32)
        val macKey = sharedSecrets.copyOfRange(32, 64)

        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(macKey, "HmacSHA256"))
        val ourMac = hmac.doFinal(ivAndCipherText)
        require(ourMac.copyOfRange(0, 32).contentEquals(mac)) { "Invalid MAC" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(cipherText)

        return ProvisionMessage.parseFrom(decrypted)
    }

    private fun hkdfDeriveSecrets(input: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            doFinal(input)
        }
        val result = ByteArray(outputLength)
        var offset = 0
        var t = ByteArray(0)
        var i: Byte = 1
        while (offset < outputLength) {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(t)
            hmac.update(info)
            hmac.update(byteArrayOf(i))
            t = hmac.doFinal()
            val toCopy = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }
        return result
    }
}
