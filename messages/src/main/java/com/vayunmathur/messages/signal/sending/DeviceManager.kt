package com.vayunmathur.messages.signal.sending

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONObject
import java.io.IOException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DeviceManager(
    private val ws: SignalWebSocket,
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
) {
    private val protocolStore = SignalProtocolStoreImpl(
        sessionStore, identityKeyStore, preKeyStore, signedPreKeyStore, kyberPreKeyStore, senderKeyStore
    )

    private val deviceCache = mutableMapOf<String, List<Int>>()

    suspend fun getDeviceIds(recipientAci: String): List<Int> {
        deviceCache[recipientAci]?.let { return it }
        return fetchDeviceIds(recipientAci)
    }

    suspend fun refreshDevices(recipientAci: String): List<Int> {
        deviceCache.remove(recipientAci)
        return fetchDeviceIds(recipientAci)
    }

    private suspend fun fetchDeviceIds(recipientAci: String): List<Int> {
        val response = ws.sendRequest(
            "GET",
            "/v2/keys/$recipientAci/*?pq=true",
        )
        if (response.status == 404) {
            Log.w(TAG, "Recipient $recipientAci not found (404)")
            throw IOException("Recipient not registered: $recipientAci")
        }
        val responseBody = String(response.body.toByteArray())
        Log.d(TAG, "Keys response for $recipientAci: $responseBody")
        val json = JSONObject(responseBody)
        if (!json.has("devices")) {
            Log.e(TAG, "No devices in keys response: $responseBody")
            throw IOException("No devices found for $recipientAci")
        }
        val devices = json.getJSONArray("devices")
        val ids = (0 until devices.length()).map { devices.getJSONObject(it).getInt("deviceId") }
        deviceCache[recipientAci] = ids

        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            val deviceId = device.getInt("deviceId")
            processPreKeyResponse(recipientAci, deviceId, json, device)
        }

        return ids
    }

    suspend fun fetchPreKeyBundle(recipientAci: String, deviceId: Int): PreKeyBundle {
        val response = ws.sendRequest(
            "GET",
            "/v2/keys/$recipientAci/$deviceId?pq=true",
        )
        val json = JSONObject(String(response.body.toByteArray()))
        val device = json.getJSONArray("devices").getJSONObject(0)
        return parsePreKeyBundle(json, device, deviceId)
    }

    suspend fun ensureSession(recipientAci: String, deviceId: Int) {
        val address = SignalProtocolAddress(recipientAci, deviceId)
        if (sessionStore.containsSession(address)) return

        Log.d(TAG, "Building session for $recipientAci:$deviceId")
        val bundle = fetchPreKeyBundle(recipientAci, deviceId)
        val builder = SessionBuilder(protocolStore, address)
        builder.process(bundle)
    }

    suspend fun updateDeviceName(name: String, identityPublicKey: ECPublicKey) {
        Log.w(TAG, "updateDeviceName: DeviceName encoding not available")
    }

    private fun processPreKeyResponse(
        recipientAci: String,
        deviceId: Int,
        root: JSONObject,
        device: JSONObject,
    ) {
        val address = SignalProtocolAddress(recipientAci, deviceId)
        if (sessionStore.containsSession(address)) return

        try {
            val bundle = parsePreKeyBundle(root, device, deviceId)
            val builder = SessionBuilder(protocolStore, address)
            builder.process(bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process pre-key bundle for $recipientAci:$deviceId", e)
        }
    }

    private fun parsePreKeyBundle(root: JSONObject, device: JSONObject, deviceId: Int): PreKeyBundle {
        val registrationId = device.getInt("registrationId")
        val identityKeyBytes = addBase64Padding(root.getString("identityKey"))
        val identityKey = IdentityKey(identityKeyBytes, 0)

        val signedPreKey = device.getJSONObject("signedPreKey")
        val signedPreKeyId = signedPreKey.getInt("keyId")
        val signedPreKeyPublic = ECPublicKey(
            addBase64Padding(signedPreKey.getString("publicKey"))
        )
        val signedPreKeySignature = addBase64Padding(signedPreKey.getString("signature"))

        val preKey = device.optJSONObject("preKey")
        val preKeyId = preKey?.getInt("keyId") ?: -1
        val preKeyPublic = preKey?.let {
            ECPublicKey(addBase64Padding(it.getString("publicKey")))
        }

        // Parse PQ (post-quantum) Kyber pre-key if present
        val pqPreKey = device.optJSONObject("pqPreKey")
        return if (pqPreKey != null) {
            try {
                val kyberPreKeyId = pqPreKey.getInt("keyId")
                val kyberPreKeyPublic = org.signal.libsignal.protocol.kem.KEMPublicKey(
                    addBase64Padding(pqPreKey.getString("publicKey"))
                )
                val kyberPreKeySignature = addBase64Padding(pqPreKey.getString("signature"))
                PreKeyBundle(
                    registrationId,
                    deviceId,
                    preKeyId,
                    preKeyPublic,
                    signedPreKeyId,
                    signedPreKeyPublic,
                    signedPreKeySignature,
                    identityKey,
                    kyberPreKeyId,
                    kyberPreKeyPublic,
                    kyberPreKeySignature
                )
            } catch (e: Exception) {
                // Fallback to non-PQ bundle if kyber parsing fails
                createNonPQBundle(registrationId, deviceId, preKeyId, preKeyPublic, signedPreKeyId, signedPreKeyPublic, signedPreKeySignature, identityKey)
            }
        } else {
            createNonPQBundle(registrationId, deviceId, preKeyId, preKeyPublic, signedPreKeyId, signedPreKeyPublic, signedPreKeySignature, identityKey)
        }
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private fun createNonPQBundle(
        registrationId: Int,
        deviceId: Int,
        preKeyId: Int,
        preKeyPublic: ECPublicKey?,
        signedPreKeyId: Int,
        signedPreKeyPublic: ECPublicKey,
        signedPreKeySignature: ByteArray,
        identityKey: IdentityKey
    ): PreKeyBundle {
        // Create a dummy Kyber key - the server will not use it since kyberPreKeyId = -1
        // Kyber1024 public key format: 0x07 (type) + 1567 bytes of key data
        val dummyKyberKey = org.signal.libsignal.protocol.kem.KEMPublicKey(
            ByteArray(1568) { if (it == 0) 0x07 else 0x00 }
        )
        return PreKeyBundle(
            registrationId,
            deviceId,
            preKeyId,
            preKeyPublic,
            signedPreKeyId,
            signedPreKeyPublic,
            signedPreKeySignature,
            identityKey,
            -1,
            dummyKyberKey,
            ByteArray(0)
        )
    }

    companion object {
        private const val TAG = "SignalSender"

        private fun addBase64Padding(data: String): ByteArray {
            val padded = when (data.length % 4) {
                2 -> data + "=="
                3 -> data + "="
                else -> data
            }
            return Base64.decode(padded, Base64.NO_WRAP)
        }

        private fun hmacSHA256(key: ByteArray, input: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(input)
        }

        private fun aes256CTR(key: ByteArray, iv: ByteArray, source: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(source)
        }

        fun encryptDeviceName(name: String, identityPublicKey: ECPublicKey): ByteArray {
            val ephemeralKeyPair = ECKeyPair.generate()
            val ephemeralPubKeyBytes = ephemeralKeyPair.publicKey.serialize()
            val masterSecret = ephemeralKeyPair.privateKey.calculateAgreement(identityPublicKey)

            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val key1 = hmacSHA256(masterSecret, "auth".toByteArray())
            val syntheticIV = hmacSHA256(key1, nameBytes).copyOfRange(0, 16)
            val key2 = hmacSHA256(masterSecret, "cipher".toByteArray())
            val cipherKey = hmacSHA256(key2, syntheticIV)
            val ciphertext = aes256CTR(cipherKey, ByteArray(16), nameBytes)

            val baos = java.io.ByteArrayOutputStream()
            val cos = com.google.protobuf.CodedOutputStream.newInstance(baos)
            cos.writeByteArray(1, ephemeralPubKeyBytes)
            cos.writeByteArray(2, syntheticIV)
            cos.writeByteArray(3, ciphertext)
            cos.flush()
            return baos.toByteArray()
        }

        fun decryptDeviceName(wrappedData: ByteArray, identityPrivateKey: ECPrivateKey): String {
            val cis = com.google.protobuf.CodedInputStream.newInstance(wrappedData)
            var ephemeralPublic: ByteArray = ByteArray(0)
            var syntheticIv: ByteArray = ByteArray(0)
            var ciphertextBytes: ByteArray = ByteArray(0)
            while (!cis.isAtEnd) {
                val tag = cis.readTag()
                when (tag ushr 3) {
                    1 -> ephemeralPublic = cis.readByteArray()
                    2 -> syntheticIv = cis.readByteArray()
                    3 -> ciphertextBytes = cis.readByteArray()
                    else -> cis.skipField(tag)
                }
            }
            val ephemeralPubKey = ECPublicKey(ephemeralPublic)
            val masterSecret = identityPrivateKey.calculateAgreement(ephemeralPubKey)

            val key2 = hmacSHA256(masterSecret, "cipher".toByteArray())
            val cipherKey = hmacSHA256(key2, syntheticIv)
            val decryptedName = aes256CTR(cipherKey, ByteArray(16), ciphertextBytes)

            val key1 = hmacSHA256(masterSecret, "auth".toByteArray())
            val expectedIV = hmacSHA256(key1, decryptedName).copyOfRange(0, 16)
            if (!MessageDigest.isEqual(expectedIV, syntheticIv)) {
                throw IllegalArgumentException("Mismatching synthetic IV")
            }
            return String(decryptedName, Charsets.UTF_8)
        }
    }
}
