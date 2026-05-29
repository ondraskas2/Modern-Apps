package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
import com.vayunmathur.email.EmailAccount
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Helper for refreshing expired Google OAuth access tokens using the refresh
 * token stored alongside each [EmailAccount]. Mirrors the token-exchange in
 * `MainActivity.exchangeCodeForToken` but uses `grant_type=refresh_token`.
 */
object TokenRefresher {

    /** Public client id used during the initial OAuth flow. */
    const val CLIENT_ID =
        "827025129169-kgv8s8dvhd7req7ao2ila1j169r068pp.apps.googleusercontent.com"

    /**
     * Refreshes the access token for [account] and persists the new value.
     * Returns the updated [EmailAccount] (with the new `accessToken` and
     * `expiresAt`) or `null` if the refresh failed.
     */
    suspend fun refresh(context: Context, account: EmailAccount): EmailAccount? {
        if (account.authType != "oauth2") {
            Log.d("TokenRefresher", "Skipping refresh for non-OAuth2 account ${account.email}")
            return null
        }
        val refreshToken = account.refreshToken
        if (refreshToken.isNullOrBlank()) {
            Log.w("TokenRefresher", "No refresh token for ${account.email} — re-login required")
            return null
        }
        return try {
            HttpClient(CIO) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }.use { client ->
                val resp = client.submitForm(
                    url = "https://oauth2.googleapis.com/token",
                    formParameters = parameters {
                        append("client_id", CLIENT_ID)
                        // iOS / Android OAuth client types refresh without a
                        // client_secret. (Desktop clients require one — see the
                        // README for client registration steps.)
                        append("refresh_token", refreshToken)
                        append("grant_type", "refresh_token")
                    },
                )
                if (!resp.status.isSuccess()) {
                    Log.w("TokenRefresher", "Refresh failed for ${account.email}: ${resp.status} ${resp.bodyAsText()}")
                    return null
                }
                val body: RefreshResponse = resp.body()
                Log.d("TokenRefresher", "New token for ${account.email}: scope=${body.scope}, expires_in=${body.expiresIn}s, access_token starts ${body.accessToken.take(12)}…")
                val updated = account.copy(
                    accessToken = body.accessToken,
                    // Google usually doesn't return a new refresh_token; keep the old one.
                    refreshToken = body.refreshToken ?: account.refreshToken,
                    expiresAt = System.currentTimeMillis() + body.expiresIn * 1000L,
                )
                EmailDatabase.getInstance(context).emailDao().insertAccount(updated)
                Log.d("TokenRefresher", "Refreshed token for ${account.email}")
                updated
            }
        } catch (e: Exception) {
            Log.e("TokenRefresher", "Refresh exception for ${account.email}: ${e.message}", e)
            null
        }
    }

    @Serializable
    private data class RefreshResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("token_type") val tokenType: String,
        @SerialName("scope") val scope: String? = null,
    )
}
