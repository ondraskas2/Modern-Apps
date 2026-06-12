package com.vayunmathur.messages.signal.auth

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.delay

object PreKeyManager {
    private const val TAG = "PreKeyManager"
    private const val BATCH_SIZE = 100
    private const val MIN_KEY_COUNT = BATCH_SIZE / 2
    private const val ACI_KEYS_PATH = "/v2/keys?identity=aci"
    private const val PNI_KEYS_PATH = "/v2/keys?identity=pni"

    private data class PreKeyCountResult(val ecCount: Int, val pqCount: Int)

    private data class PreKeyResponse(
        val identityKey: String,
        val devices: List<PreKeyDevice>,
    )

    private data class PreKeyDevice(
        val deviceId: Int,
        val registrationId: Int,
        val signedPreKey: PreKeyDetail,
        val preKey: PreKeyDetail?,
        val pqPreKey: PreKeyDetail?,
    )

    private data class PreKeyDetail(
        val keyId: Int,
        val publicKey: String,
        val signature: String?,
    )

    private fun keysPath(identity: String): String {
        return if (identity == "pni") PNI_KEYS_PATH else ACI_KEYS_PATH
    }

    private fun addBase64PaddingAndDecode(data: String): ByteArray {
        var padded = data
        val padding = padded.length % 4
        if (padding > 0) {
            padded += "=".repeat(4 - padding)
        }
        return Base64.decode(padded, Base64.NO_WRAP)
    }

    suspend fun generateAndUploadPreKeys(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        uploadKeysForIdentity(ws, preKeyStore, "aci", aciIdentityKeyPair)
        uploadKeysForIdentity(ws, preKeyStore, "pni", pniIdentityKeyPair)
        Log.d(TAG, "Pre-keys uploaded for both identities")
    }

    suspend fun checkAndRefreshIfNeeded(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        val aciCounts = getPreKeyCount(ws, "aci")
        val pniCounts = getPreKeyCount(ws, "pni")
        Log.d(TAG, "Pre-key counts: aci=(ec=${aciCounts.ecCount}, pq=${aciCounts.pqCount}), pni=(ec=${pniCounts.ecCount}, pq=${pniCounts.pqCount})")

        var aciNeedsUpload = false
        var pniNeedsUpload = false

        if (aciCounts.ecCount < MIN_KEY_COUNT || aciCounts.pqCount < MIN_KEY_COUNT) {
            aciNeedsUpload = true
        }
        if (pniCounts.ecCount < MIN_KEY_COUNT || pniCounts.pqCount < MIN_KEY_COUNT) {
            pniNeedsUpload = true
        }

        if (aciNeedsUpload) {
            uploadKeysForIdentity(ws, preKeyStore, "aci", aciIdentityKeyPair)
        }
        if (pniNeedsUpload) {
            uploadKeysForIdentity(ws, preKeyStore, "pni", pniIdentityKeyPair)
        }
    }

    suspend fun keyCheckLoop(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        var windowStartMinutes = 0
        var windowSizeMinutes = 1
        var firstRun = true
        while (true) {
            val waitMinutes = if (firstRun) {
                firstRun = false
                0
            } else {
                val random = SecureRandom()
                random.nextInt(windowSizeMinutes) + windowStartMinutes
            }
            if (waitMinutes > 0) {
                Log.d(TAG, "Waiting $waitMinutes minutes before next key check")
                delay(waitMinutes * 60_000L)
            }
            try {
                checkAndRefreshIfNeeded(ws, preKeyStore, aciIdentityKeyPair, pniIdentityKeyPair)
                // After success, check again in 36–60 hours
                windowStartMinutes = 36 * 60
                windowSizeMinutes = 24 * 60
            } catch (e: IOException) {
                if (e.message?.contains("PNI prekey upload rejected (422)") == true) {
                    Log.e(TAG, "Got 422 on PNI prekey upload, session invalid")
                    throw e
                }
                Log.w(TAG, "Key check failed, retrying in 5-30 minutes: ${e.message}")
                windowStartMinutes = 5
                windowSizeMinutes = 25
            } catch (e: Exception) {
                Log.w(TAG, "Key check failed, retrying in 5-30 minutes: ${e.message}")
                windowStartMinutes = 5
                windowSizeMinutes = 25
            }
        }
    }

    private suspend fun getPreKeyCount(ws: SignalWebSocket, identity: String): PreKeyCountResult {
        val response = ws.sendRequest("GET", keysPath(identity))
        if (response.status < 200 || response.status >= 300) {
            throw IOException("Fetching prekey counts failed: ${response.status}")
        }
        val json = JSONObject(response.body.toStringUtf8())
        return PreKeyCountResult(json.optInt("count", 0), json.optInt("pqCount", 0))
    }

    private suspend fun uploadKeysForIdentity(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        identity: String,
        identityKeyPair: IdentityKeyPair,
    ) {
        val (ecStoreCount, nextPreKeyId) = preKeyStore.getNextPreKeyId()
        val (kyberStoreCount, nextKyberPreKeyId) = preKeyStore.getNextKyberPreKeyId()

        val preKeys = JSONArray()

        val existingPreKeys = preKeyStore.getAllPreKeys()
        for (record in existingPreKeys) {
            preKeys.put(JSONObject().apply {
                put("keyId", record.id)
                put("publicKey", Base64.encodeToString(record.keyPair.publicKey.serialize(), Base64.NO_WRAP))
            })
        }

        if (ecStoreCount < BATCH_SIZE) {
            val ecToGenerate = BATCH_SIZE - ecStoreCount
            for (i in 0 until ecToGenerate) {
                val kp = ECKeyPair.generate()
                val id = nextPreKeyId + i
                val record = PreKeyRecord(id, kp)
                preKeyStore.storePreKey(id, record)
                preKeys.put(JSONObject().apply {
                    put("keyId", id)
                    put("publicKey", Base64.encodeToString(kp.publicKey.serialize(), Base64.NO_WRAP))
                })
            }
        }

        val pqPreKeys = JSONArray()

        val existingKyberKeys = preKeyStore.getAllNormalKyberPreKeys()
        for (record in existingKyberKeys) {
            pqPreKeys.put(JSONObject().apply {
                put("keyId", record.id)
                put("publicKey", Base64.encodeToString(record.keyPair.publicKey.serialize(), Base64.NO_WRAP))
                put("signature", Base64.encodeToString(record.signature, Base64.NO_WRAP))
            })
        }

        if (kyberStoreCount < BATCH_SIZE) {
            val kyberToGenerate = BATCH_SIZE - kyberStoreCount
            for (i in 0 until kyberToGenerate) {
                val kemKp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val sig = identityKeyPair.privateKey.calculateSignature(kemKp.publicKey.serialize())
                val id = nextKyberPreKeyId + i
                val record = KyberPreKeyRecord(id, System.currentTimeMillis(), kemKp, sig)
                preKeyStore.storeKyberPreKey(id, record)
                pqPreKeys.put(JSONObject().apply {
                    put("keyId", id)
                    put("publicKey", Base64.encodeToString(kemKp.publicKey.serialize(), Base64.NO_WRAP))
                    put("signature", Base64.encodeToString(sig, Base64.NO_WRAP))
                })
            }
        }

        val payload = JSONObject().apply {
            put("preKeys", preKeys)
            put("pqPreKeys", pqPreKeys)
            put("identityKey", Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))
        }

        val response = ws.sendRequest(
            method = "PUT",
            path = keysPath(identity),
            body = payload.toString().toByteArray(),
        )
        if (response.status == 422 && identity == "pni") {
            Log.e(TAG, "Got 422 on PNI prekey upload - account may be logged out")
            throw IOException("PNI prekey upload rejected (422), re-provisioning required")
        }
        if (response.status !in 200..299) {
            throw IOException("Pre-key upload failed for $identity: ${response.status}")
        }
        Log.d(TAG, "Uploaded $identity pre-keys")
    }

    suspend fun fetchAndProcessPreKey(
        ws: SignalWebSocket,
        theirServiceId: String,
        specificDeviceId: Int = -1,
        sessionStore: com.vayunmathur.messages.signal.store.SignalSessionStore,
        identityKeyStore: com.vayunmathur.messages.signal.store.SignalIdentityKeyStore,
        preKeyStore: SignalPreKeyStore,
    ) {
        val deviceIdPath = if (specificDeviceId >= 0) "/$specificDeviceId" else "/*"
        val path = "/v2/keys/$theirServiceId$deviceIdPath?pq=true"
        val response = ws.sendRequest("GET", path)
        if (response.status == 404) {
            throw IOException("User $theirServiceId is unregistered (404)")
        }
        if (response.status !in 200..299) {
            throw IOException("Failed to fetch pre-keys for $theirServiceId: ${response.status}")
        }
        val json = JSONObject(response.body.toStringUtf8())
        val identityKeyBytes = addBase64PaddingAndDecode(json.getString("identityKey"))
        val identityKey = org.signal.libsignal.protocol.IdentityKey(identityKeyBytes)

        val devices = json.getJSONArray("devices")
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val deviceId = d.getInt("deviceId")
            val registrationId = d.getInt("registrationId")

            val signedPreKeyObj = d.getJSONObject("signedPreKey")
            val signedPreKeyId = signedPreKeyObj.getInt("keyId")
            val signedPublicKeyBytes = addBase64PaddingAndDecode(signedPreKeyObj.getString("publicKey"))
            val signedPublicKey = ECPublicKey(signedPublicKeyBytes)
            val signatureBytes = addBase64PaddingAndDecode(signedPreKeyObj.getString("signature"))

            var preKeyId = 0
            var preKeyPublic: ECPublicKey? = null
            if (d.has("preKey") && !d.isNull("preKey")) {
                val preKeyObj = d.getJSONObject("preKey")
                preKeyId = preKeyObj.getInt("keyId")
                preKeyPublic = ECPublicKey(addBase64PaddingAndDecode(preKeyObj.getString("publicKey")))
            }

            val pqPreKeyObj = d.optJSONObject("pqPreKey")
            val bundle = if (pqPreKeyObj != null) {
                try {
                    val kyberPreKeyId = pqPreKeyObj.getInt("keyId")
                    val kyberPreKeyPublic = org.signal.libsignal.protocol.kem.KEMPublicKey(
                        addBase64PaddingAndDecode(pqPreKeyObj.getString("publicKey"))
                    )
                    val kyberPreKeySignature = addBase64PaddingAndDecode(pqPreKeyObj.getString("signature"))
                    PreKeyBundle(
                        registrationId, deviceId,
                        preKeyId, preKeyPublic,
                        signedPreKeyId, signedPublicKey, signatureBytes,
                        identityKey,
                        kyberPreKeyId, kyberPreKeyPublic, kyberPreKeySignature,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Kyber pre-key, falling back to non-PQ bundle", e)
                    PreKeyBundle(
                        registrationId, deviceId,
                        preKeyId, preKeyPublic,
                        signedPreKeyId, signedPublicKey, signatureBytes,
                        identityKey,
                        -1,
                        org.signal.libsignal.protocol.kem.KEMPublicKey(ByteArray(1568) { if (it == 0) 0x07 else 0x00 }),
                        ByteArray(0),
                    )
                }
            } else {
                PreKeyBundle(
                    registrationId, deviceId,
                    preKeyId, preKeyPublic,
                    signedPreKeyId, signedPublicKey, signatureBytes,
                    identityKey,
                    -1,
                    org.signal.libsignal.protocol.kem.KEMPublicKey(ByteArray(1568) { if (it == 0) 0x07 else 0x00 }),
                    ByteArray(0),
                )
            }
            val address = SignalProtocolAddress(theirServiceId, deviceId)
            val builder = SessionBuilder(sessionStore, preKeyStore, preKeyStore, identityKeyStore, address)
            builder.process(bundle)
        }
        Log.d(TAG, "Processed pre-key bundle for $theirServiceId")
    }
}
