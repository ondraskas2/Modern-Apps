package com.vayunmathur.messages.gmessages

import android.content.Context
import android.util.Base64
import com.google.protobuf.ByteString
import com.vayunmathur.library.util.DataStoreUtils
import authentication.Authentication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted authentication state for a Google-Messages-for-Web session.
 *
 * Direct port of the `AuthData` struct in
 * `/Users/vayun/Documents/gmessages/pkg/libgm/client.go` — minus the
 * Google-account / GAIA pairing fields we don't support in v1.
 *
 * Lifecycle:
 *   - On first launch (no persisted blob), the [GMessagesClient] starts
 *     with [generateInitial] and persists after a successful pair.
 *   - On every change (post-pair, post-token-refresh), we re-serialize
 *     and write back via [save].
 *   - On app launch with a persisted blob, [load] reconstitutes the
 *     entire session so we don't have to re-pair.
 */
@Serializable
data class AuthData(
    /** AES + HMAC keys shared with the paired phone. Bodies are
     *  encrypted/decrypted with these on the wire. */
    val requestCrypto: AesCtrHmacBlob,

    /** Long-lived ECDSA P-256 key used to sign token-refresh requests. */
    val refreshKey: EcdsaP256Jwk,

    /** Tachyon auth token returned by RegisterPhoneRelay and refreshed
     *  periodically via Pairing/RefreshPhoneRelay. base64 of raw bytes. */
    val tachyonAuthTokenB64: String? = null,

    /** Epoch-ms when the tachyon token expires (TTL from the relay). */
    val tachyonExpiryMs: Long = 0L,

    /** TTL in microseconds (per the Tachyon RPC field convention). */
    val tachyonTtlUs: Long = 0L,

    /** Identity of the paired phone, as base64-encoded
     *  `authentication.Device` protobuf. Sent in every OutgoingRPCMessage
     *  so the relay knows which phone to push to. */
    val mobileDeviceB64: String? = null,

    /** Identity of THIS browser-side device (us). Returned by the relay
     *  after pairing completes. base64 of `authentication.Device` proto. */
    val browserDeviceB64: String? = null,

    /** Stable session ID used as the sessionID on every outgoing RPC.
     *  Regenerated on each fresh pair. */
    val sessionId: String,

    /** GAIA destination registration UUID. Non-null for Google-account pairing. */
    val destRegId: String? = null,

    /** GAIA pairing attempt UUID. */
    val pairingId: String? = null,

    /** Cookies for Google-account (GAIA) authentication. */
    val cookies: Map<String, String>? = null,

    /** Web encryption key (unused but stored for completeness). */
    val webEncryptionKeyB64: String? = null,
) {

    fun isPaired(): Boolean = !tachyonAuthTokenB64.isNullOrBlank() && browserDeviceB64 != null

    fun isGoogleAccount(): Boolean = !destRegId.isNullOrBlank()

    fun authNetwork(): String = if (isGoogleAccount()) "GDitto" else ""

    fun hasCookies(): Boolean = !cookies.isNullOrEmpty()

    /** Decode the tachyon token back to raw bytes. */
    fun tachyonToken(): ByteArray? =
        tachyonAuthTokenB64?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Decode the paired phone's device proto. */
    fun mobile(): Authentication.Device? = mobileDeviceB64?.let {
        Authentication.Device.parseFrom(Base64.decode(it, Base64.NO_WRAP))
    }

    /** Decode the browser-side device proto. */
    fun browser(): Authentication.Device? = browserDeviceB64?.let {
        Authentication.Device.parseFrom(Base64.decode(it, Base64.NO_WRAP))
    }

    /** Materialize the AES helper from the persisted key blob. */
    fun crypto(): AesCtrHmac = AesCtrHmac(
        aesKey = Base64.decode(requestCrypto.aesKeyB64, Base64.NO_WRAP),
        hmacKey = Base64.decode(requestCrypto.hmacKeyB64, Base64.NO_WRAP),
    )

    /** Update the mutable AES/HMAC keys (used by GAIA key derivation). */
    fun withCryptoKeys(aesKey: ByteArray, hmacKey: ByteArray): AuthData = copy(
        requestCrypto = AesCtrHmacBlob(
            aesKeyB64 = Base64.encodeToString(aesKey, Base64.NO_WRAP),
            hmacKeyB64 = Base64.encodeToString(hmacKey, Base64.NO_WRAP),
        ),
    )

    suspend fun save(context: Context) {
        val json = Json.encodeToString(serializer(), this)
        DataStoreUtils.getInstance(context).setString(DATA_STORE_KEY, json)
    }

    companion object {
        private const val DATA_STORE_KEY = "gmessages_auth_data"

        /** Build a fresh auth state suitable for kicking off a new pair. */
        fun generateInitial(): AuthData {
            val aes = AesCtrHmac.generate()
            return AuthData(
                requestCrypto = AesCtrHmacBlob(
                    aesKeyB64 = Base64.encodeToString(aes.aesKey, Base64.NO_WRAP),
                    hmacKeyB64 = Base64.encodeToString(aes.hmacKey, Base64.NO_WRAP),
                ),
                refreshKey = EcdsaP256Jwk.generate(),
                sessionId = java.util.UUID.randomUUID().toString(),
            )
        }

        suspend fun load(context: Context): AuthData? {
            val json = DataStoreUtils.getInstance(context).getString(DATA_STORE_KEY) ?: return null
            if (json.isBlank()) return null
            return runCatching { Json.decodeFromString(serializer(), json) }.getOrNull()
        }
    }
}

/** AES + HMAC key pair serialized as base64 strings. */
@Serializable
data class AesCtrHmacBlob(
    val aesKeyB64: String,
    val hmacKeyB64: String,
)

/** Convenience extension: wrap raw bytes as a protobuf ByteString. */
fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)
