package com.vayunmathur.messages.telegram

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TelegramAuthData(
    val phoneNumber: String,
    val loggedIn: Boolean,
    val authKey: String? = null,
    val authKeyId: String? = null,
    val salt: Long? = null,
    val dc: Int? = null,
    val serverAddress: String? = null,
    val sessionId: Long? = null,
) {
    suspend fun save(context: Context) {
        DataStoreUtils.getInstance(context).setString(
            DATA_STORE_KEY,
            Json.encodeToString(serializer(), this),
        )
    }

    companion object {
        private const val DATA_STORE_KEY = "telegram_auth_data"

        suspend fun load(context: Context): TelegramAuthData? {
            val json = DataStoreUtils.getInstance(context).getString(DATA_STORE_KEY) ?: return null
            if (json.isBlank()) return null
            return runCatching { Json.decodeFromString(serializer(), json) }.getOrNull()
        }

        suspend fun clear(context: Context) {
            DataStoreUtils.getInstance(context).setString(DATA_STORE_KEY, "")
        }
    }
}
