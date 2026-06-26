package com.vayunmathur.messages.whatsapp

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson

/**
 * Persistent auth data for WhatsApp Web companion device.
 * Stores Noise key pair, Signal Protocol identity, and session data.
 *
 * Based on whatsmeow/store/clientpayload.go and store/store.go
 */
@Serializable
data class WhatsAppAuthData(
    val phoneNumber: String,
    val pushName: String,
    val wid: String,
    // Noise key pair (X25519) — used in every handshake
    val noisePrivateKey: String, // Base64
    val noisePublicKey: String, // Base64
    // Signal identity key pair (Curve25519) — used for E2E encryption
    val identityPrivateKey: String, // Base64
    val identityPublicKey: String, // Base64
    // Signal Protocol registration ID
    val registrationId: Int,
    // Signal signed pre-key
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String, // Base64
    val signedPreKeyPrivate: String, // Base64
    val signedPreKeySignature: String, // Base64
    // ADV secret key (used in QR pairing)
    val advSecretKey: String, // Base64
    // Device ID assigned by server after pairing (from Go UserLoginMetadata.WADeviceID)
    val deviceId: Int = 0,
    // Timezone of user (from Go UserLoginMetadata.Timezone)
    val timezone: String = "",
    // Timestamp when the user logged in (from Go UserLoginMetadata.LoggedInAt)
    val loggedInAt: Long = 0L,
    // Phone last seen time (from Go UserLoginMetadata.PhoneLastSeen)
    val phoneLastSeen: Long = 0L,
    // Phone last pinged time (from Go UserLoginMetadata.PhoneLastPinged)
    val phoneLastPinged: Long = 0L,
    // Platform type (from Go store.DeviceProps.PlatformType)
    val platformType: String = "WEB",
    // LID (Linked Identity) JID for this device
    val lid: String = "",
    // Base64 self-signed ADV device identity (waAdv.ADVSignedDeviceIdentity) from pairing.
    // Sent in the device-identity node alongside pkmsg sends. Empty until paired.
    val accountSignedDeviceIdentity: String = "",
) {
    companion object {
        private const val PREFS_NAME = "whatsapp_auth"
        private const val KEY_AUTH_DATA = "auth_data"

        fun load(context: Context): WhatsAppAuthData? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_AUTH_DATA, null) ?: return null
            return try {
                KotlinJson.decodeFromString<WhatsAppAuthData>(json)
            } catch (e: Exception) {
                null
            }
        }

        fun save(context: Context, authData: WhatsAppAuthData) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = KotlinJson.encodeToString(authData)
            prefs.edit { putString(KEY_AUTH_DATA, json) }
        }

        fun clear(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { remove(KEY_AUTH_DATA) }
        }
    }
}
