package com.vayunmathur.everysync.auth

import com.vayunmathur.everysync.BuildConfig

/**
 * OAuth2 endpoints + scopes for a provider.
 *
 * Everything here is safe to commit — client IDs are public identifiers, not
 * secrets, so builds stay reproducible. Providers that require a confidential
 * client secret (Withings) never ship the secret in the APK: their token
 * exchange is relayed through [tokenProxyUrl], a small backend that injects the
 * secret server-side.
 */
data class OAuthConfig(
    val authEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    /**
     * If non-empty, token exchange/refresh POSTs go here instead of
     * [tokenEndpoint] so a confidential secret can be added server-side. Keeps
     * secrets out of the (reproducible) build.
     */
    val tokenProxyUrl: String = "",
    /** Extra query params appended to the authorization request. */
    val extraAuthParams: Map<String, String> = emptyMap(),
    /** Redirect URI for this provider (Google needs its reverse-DNS scheme). */
    val redirectUri: String = REDIRECT_URI,
) {
    val hasClientId: Boolean get() = clientId.isNotBlank()

    companion object {
        const val REDIRECT_URI = "com.vayunmathur.everysync:/oauth"

        val GOOGLE = OAuthConfig(
            authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            scopes = listOf(
                "https://www.googleapis.com/auth/contacts",
                "https://www.googleapis.com/auth/calendar",
            ),
            // Force a refresh token to be returned on first consent.
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
            redirectUri = BuildConfig.GOOGLE_REDIRECT_URI,
        )

        val WITHINGS = OAuthConfig(
            authEndpoint = "https://account.withings.com/oauth2_user/authorize2",
            tokenEndpoint = "https://wbsapi.withings.net/v2/oauth2",
            clientId = BuildConfig.WITHINGS_OAUTH_CLIENT_ID,
            // Withings needs a client secret; keep it off-device by relaying token
            // exchange through this backend (empty = Withings token exchange disabled).
            tokenProxyUrl = BuildConfig.WITHINGS_TOKEN_PROXY_URL,
            scopes = listOf("user.metrics", "user.activity"),
        )

        // Google Health via the Google Fitness REST API — same Google OAuth client,
        // fitness read scopes. (Google's Fit REST API is being wound down; configure
        // the client ID to use it while available.)
        val GOOGLE_FIT = OAuthConfig(
            authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            scopes = listOf(
                "https://www.googleapis.com/auth/fitness.activity.read",
                "https://www.googleapis.com/auth/fitness.body.read",
                "https://www.googleapis.com/auth/fitness.heart_rate.read",
            ),
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
            redirectUri = BuildConfig.GOOGLE_REDIRECT_URI,
        )
    }
}
