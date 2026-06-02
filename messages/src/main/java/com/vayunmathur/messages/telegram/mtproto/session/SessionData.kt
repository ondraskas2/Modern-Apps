package com.vayunmathur.messages.telegram.mtproto.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SessionData(
    val dc: Int,
    val address: String,
    val authKey: String,   // Base64-encoded 256-byte key
    val authKeyId: String, // Base64-encoded 8 bytes
    val salt: Long,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): SessionData = Json.decodeFromString(serializer(), json)
    }
}
