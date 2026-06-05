package com.vayunmathur.passwords.ui

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.util.Cbor
import com.vayunmathur.passwords.util.PasskeyCredentialService
import com.vayunmathur.passwords.util.PasskeyUtils
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

class PasskeyAuthActivity : FragmentActivity() {

    private val db by lazy {
        applicationContext.buildDatabase<PasswordDatabase>()
    }
    private val passkeyDao by lazy { db.passkeyDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val flow = intent.getStringExtra(EXTRA_FLOW)
        try {
            when (flow) {
                FLOW_CREATE -> handleCreate()
                FLOW_GET -> handleGet()
                FLOW_PASSWORD -> handlePassword()
                else -> {
                    Log.e(TAG, "Unknown flow: $flow")
                    setResult(RESULT_CANCELED)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in passkey $flow flow", e)
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun handleCreate() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: run {
            Log.e(TAG, "No create credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val publicKeyRequest = request.callingRequest as? CreatePublicKeyCredentialRequest ?: run {
            Log.e(TAG, "Request is not a PublicKeyCredentialRequest")
            setResult(RESULT_CANCELED)
            return
        }

        val json = JSONObject(publicKeyRequest.requestJson)
        val rp = json.getJSONObject("rp")
        val rpId = rp.getString("id")
        val rpName = rp.optString("name", rpId)
        val user = json.getJSONObject("user")
        val userId = user.getString("id")
        val userName = user.optString("name", "")
        val userDisplayName = user.optString("displayName", userName)
        val challenge = json.getString("challenge")

        // Privileged browsers provide clientDataHash directly
        val callingAppInfo = request.callingAppInfo
        val privilegedOrigin = PasskeyUtils.getPrivilegedOrigin(callingAppInfo, applicationContext)
        val isPrivileged = privilegedOrigin != null
        val origin = privilegedOrigin ?: PasskeyUtils.getAndroidOrigin(callingAppInfo)
        Log.d(TAG, "Create passkey for rpId=$rpId, origin=$origin, privileged=$isPrivileged")

        // Generate EC P-256 key pair
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val ecPublicKey = keyPair.public as ECPublicKey

        val credentialId = PasskeyUtils.generateCredentialId()
        val credentialIdB64 = b64Url(credentialId)

        // Build COSE public key
        val xBytes = toFixedBytes(ecPublicKey.w.affineX, 32)
        val yBytes = toFixedBytes(ecPublicKey.w.affineY, 32)
        val coseKeyMap = linkedMapOf<Any, Any>(
            1L to 2L,    // kty: EC2
            3L to -7L,   // alg: ES256
            -1L to 1L,   // crv: P-256
            -2L to xBytes as Any, // x
            -3L to yBytes as Any, // y
        )
        val coseKeyBytes = Cbor.encode(coseKeyMap)

        // Build authenticator data with attested credential data
        val authDataBase = PasskeyUtils.buildAuthenticatorData(
            rpId = rpId,
            attestedCredentialData = true,
        )
        val authData = authDataBase +
            AAGUID +
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) +
            credentialId +
            coseKeyBytes

        // Build attestation object using CBOR
        val attestationObject = Cbor.encode(linkedMapOf<String, Any>(
            "fmt" to "none",
            "attStmt" to emptyMap<Any, Any>(),
            "authData" to authData,
        ))

        // For privileged browsers: use placeholder clientDataJSON (browser replaces it)
        // For Android apps: build our own clientDataJSON
        val clientDataJsonB64 = if (isPrivileged) {
            b64Url("<placeholder>".toByteArray())
        } else {
            val clientDataJson = JSONObject().apply {
                put("type", "webauthn.create")
                put("challenge", challenge)
                put("origin", origin)
                put("crossOrigin", false)
            }.toString()
            b64Url(clientDataJson.toByteArray())
        }

        val responseJson = JSONObject().apply {
            put("id", credentialIdB64)
            put("rawId", credentialIdB64)
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put("response", JSONObject().apply {
                put("clientDataJSON", clientDataJsonB64)
                put("attestationObject", b64Url(attestationObject))
                put("transports", JSONArray(listOf("internal", "hybrid")))
                put("publicKeyAlgorithm", -7)
                put("publicKey", b64Url(keyPair.public.encoded))
                put("authenticatorData", b64Url(authData))
            })
            put("clientExtensionResults", JSONObject())
        }.toString()

        runBlocking {
            passkeyDao.upsert(
                Passkey(
                    rpId = rpId,
                    rpName = rpName,
                    credentialId = credentialIdB64,
                    userId = userId,
                    userName = userName,
                    userDisplayName = userDisplayName,
                    privateKeyBytes = keyPair.private.encoded,
                    creationTime = System.currentTimeMillis(),
                    lastUsedTime = System.currentTimeMillis(),
                    signCount = 0,
                )
            )
        }

        Log.d(TAG, "Passkey created successfully for rpId=$rpId, credId=$credentialIdB64")
        val credentialResponse = androidx.credentials.CreatePublicKeyCredentialResponse(responseJson)
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, credentialResponse)
        setResult(RESULT_OK, result)
    }

    private fun handleGet() {
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: run {
            Log.e(TAG, "No get credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID) ?: run {
            Log.e(TAG, "No credential ID in intent")
            setResult(RESULT_CANCELED)
            return
        }

        val passkey = runBlocking { passkeyDao.getByCredentialId(credentialId) } ?: run {
            Log.e(TAG, "Passkey not found for credentialId=$credentialId")
            setResult(RESULT_CANCELED)
            return
        }

        val publicKeyOption = providerRequest.credentialOptions
            .filterIsInstance<GetPublicKeyCredentialOption>()
            .firstOrNull() ?: run {
            Log.e(TAG, "No PublicKeyCredentialOption in request")
            setResult(RESULT_CANCELED)
            return
        }

        val json = JSONObject(publicKeyOption.requestJson)
        val challenge = json.getString("challenge")

        val callingAppInfo = providerRequest.callingAppInfo
        val privilegedOrigin = PasskeyUtils.getPrivilegedOrigin(callingAppInfo, applicationContext)
        val isPrivileged = privilegedOrigin != null
        val origin = privilegedOrigin ?: PasskeyUtils.getAndroidOrigin(callingAppInfo)
        Log.d(TAG, "Get passkey for rpId=${passkey.rpId}, origin=$origin, privileged=$isPrivileged")

        val newSignCount = passkey.signCount + 1
        val authenticatorData = PasskeyUtils.buildAuthenticatorData(
            rpId = passkey.rpId,
            signCount = newSignCount,
        )

        // For privileged browsers: use the browser's clientDataHash
        // For Android apps: compute from our own clientDataJSON
        val clientDataHash: ByteArray
        val clientDataJsonB64: String
        if (isPrivileged) {
            val providedHash = try { publicKeyOption.clientDataHash } catch (_: Exception) { null }
            if (providedHash != null) {
                clientDataHash = providedHash
            } else {
                val cdj = JSONObject().apply {
                    put("type", "webauthn.get")
                    put("challenge", challenge)
                    put("origin", origin)
                    put("crossOrigin", false)
                }.toString()
                clientDataHash = MessageDigest.getInstance("SHA-256").digest(cdj.toByteArray())
            }
            clientDataJsonB64 = b64Url("<placeholder>".toByteArray())
        } else {
            val clientDataJson = JSONObject().apply {
                put("type", "webauthn.get")
                put("challenge", challenge)
                put("origin", origin)
                put("crossOrigin", false)
            }.toString()
            clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray())
            clientDataJsonB64 = b64Url(clientDataJson.toByteArray())
        }

        val signedData = authenticatorData + clientDataHash
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(passkey.privateKeyBytes))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(signedData)
        val sig = signature.sign()

        runBlocking {
            passkeyDao.upsert(
                passkey.copy(
                    signCount = newSignCount,
                    lastUsedTime = System.currentTimeMillis(),
                )
            )
        }

        val credIdBytes = Base64.decode(
            passkey.credentialId,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val responseJson = JSONObject().apply {
            put("id", passkey.credentialId)
            put("rawId", b64Url(credIdBytes))
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put("response", JSONObject().apply {
                put("clientDataJSON", clientDataJsonB64)
                put("authenticatorData", b64Url(authenticatorData))
                put("signature", b64Url(sig))
                put("userHandle", passkey.userId)
            })
            put("clientExtensionResults", JSONObject())
        }.toString()

        Log.d(TAG, "Passkey assertion successful for rpId=${passkey.rpId}")
        val credentialResponse = PublicKeyCredential(responseJson)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(credentialResponse),
        )
        setResult(RESULT_OK, result)
    }

    private fun b64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun toFixedBytes(value: java.math.BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }

    private fun handlePassword() {
        val passwordId = intent.getLongExtra(PasskeyCredentialService.EXTRA_PASSWORD_ID, -1)
        if (passwordId == -1L) {
            Log.e(TAG, "No password ID in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val password = runBlocking { db.passwordDao().getById(passwordId) }
        if (password == null) {
            Log.e(TAG, "Password not found for id=$passwordId")
            setResult(RESULT_CANCELED)
            return
        }
        val credentialResponse = androidx.credentials.PasswordCredential(password.userId, password.password)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(credentialResponse),
        )
        setResult(RESULT_OK, result)
    }

    companion object {
        const val EXTRA_FLOW = "flow"
        const val EXTRA_CREDENTIAL_ID = "credential_id"
        const val FLOW_CREATE = "create"
        const val FLOW_GET = "get"
        const val FLOW_PASSWORD = "password"
        private const val TAG = "PasskeyAuthActivity"
        private val AAGUID = uuidToBytes(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))

        private fun uuidToBytes(uuid: UUID): ByteArray {
            val bytes = ByteArray(16)
            var msb = uuid.mostSignificantBits
            var lsb = uuid.leastSignificantBits
            for (i in 0..7) {
                bytes[7 - i] = (msb and 0xFF).toByte()
                msb = msb shr 8
            }
            for (i in 0..7) {
                bytes[15 - i] = (lsb and 0xFF).toByte()
                lsb = lsb shr 8
            }
            return bytes
        }
    }
}
