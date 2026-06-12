package com.vayunmathur.messages.gmessages

import android.util.Base64
import android.util.Log
import authentication.Authentication
import authentication.Authentication.AuthenticationContainer
import authentication.Authentication.AuthMessage
import authentication.Authentication.BrowserType
import authentication.Authentication.BrowserDetails
import authentication.Authentication.ConfigVersion
import authentication.Authentication.DeviceType
import authentication.Authentication.ECDSAKeys
import authentication.Authentication.KeyData
import authentication.Authentication.RegisterPhoneRelayResponse
import authentication.Authentication.URLData
import java.util.UUID

/**
 * Implements the QR-code pairing handshake. Direct port of
 * `pkg/libgm/pair.go`.
 *
 * Flow:
 *   1. POST to Pairing/RegisterPhoneRelay with our ECDSA public key
 *      (X.509 SubjectPublicKeyInfo encoding) — the relay returns an
 *      initial tachyon auth token and a one-shot pairing key.
 *   2. Build a URLData{ pairingKey, AESKey, HMACKey } protobuf,
 *      base64-encode it (standard, NOT url-safe), and prefix with
 *      [Endpoints.QrCodeBaseUrl].
 *   3. Render that URL as a QR code (caller's responsibility) and show
 *      it. The user scans it from their phone's Google Messages app
 *      (menu → Device pairing → Pair QR code scanner).
 *   4. The phone POSTs the pair completion to the relay, which pushes
 *      a Paired event back through our long-poll. [LongPoll] handles
 *      that and calls [AuthData.applyPaired].
 */
object PairFlow {

    private const val TAG = "GMessages/Pair"

    /** Network name on the QR-pair path (vs. "GDitto" for the
     *  Google-account-based GAIA path we don't support). */
    const val QrNetwork = "Bugle"

    /** Config version that messages.google.com/web is currently sending.
     *  Kept in sync with libgm `util/config.go.ConfigMessage`. Lazy so
     *  the proto runtime is initialized before we touch the builders. */
    val ConfigVersion: ConfigVersion by lazy {
        authentication.Authentication.ConfigVersion.newBuilder()
            .setYear(2026).setMonth(3).setDay(18).setV1(4).setV2(6)
            .build()
    }

    /** Browser-identification protobuf sent on every auth request. */
    val BrowserDetails: BrowserDetails by lazy {
        authentication.Authentication.BrowserDetails.newBuilder()
            .setUserAgent(Endpoints.UserAgent)
            .setBrowserType(BrowserType.OTHER)
            .setOS("libgm")
            .setDeviceType(DeviceType.TABLET)
            .build()
    }

    /**
     * Step 1 + 2: register a phone-relay slot and return both the
     * initial tachyon token (so the long-poll can immediately connect
     * to receive the eventual Paired event) and the QR URL the user
     * should scan.
     */
    suspend fun registerAndBuildQrUrl(
        rpc: RpcClient,
        auth: AuthData,
    ): RegisterResult {
        val pubKey = auth.refreshKey.publicKeyDer()
        val req = AuthenticationContainer.newBuilder()
            .setAuthMessage(
                AuthMessage.newBuilder()
                    .setRequestID(UUID.randomUUID().toString())
                    .setNetwork(QrNetwork)
                    .setConfigVersion(ConfigVersion)
            )
            .setBrowserDetails(BrowserDetails)
            .setKeyData(
                KeyData.newBuilder()
                    .setEcdsaKeys(
                        ECDSAKeys.newBuilder()
                            .setField1(2)
                            .setEncryptedKeys(pubKey.toByteString())
                    )
            )
            .build()

        Log.i(TAG, "RegisterPhoneRelay …")
        val resp = rpc.postProtobuf(
            url = Endpoints.RegisterPhoneRelayUrl,
            body = req,
            responseTemplate = RegisterPhoneRelayResponse.getDefaultInstance(),
        )
        Log.i(TAG, "RegisterPhoneRelay OK; tachyon token ${resp.authKeyData.tachyonAuthToken.size()} bytes")

        val urlData = URLData.newBuilder()
            .setPairingKey(resp.pairingKey)
            .setAESKey(auth.crypto().aesKey.toByteString())
            .setHMACKey(auth.crypto().hmacKey.toByteString())
            .build()
        val qrPayload = Base64.encodeToString(urlData.toByteArray(), Base64.NO_WRAP)
        val qrUrl = Endpoints.QrCodeBaseUrl + qrPayload

        return RegisterResult(
            tachyonToken = resp.authKeyData.tachyonAuthToken.toByteArray(),
            tachyonTtlUs = resp.authKeyData.ttl,
            qrUrl = qrUrl,
        )
    }

    data class RegisterResult(
        val tachyonToken: ByteArray,
        val tachyonTtlUs: Long,
        val qrUrl: String,
    )

    /**
     * Refresh the tachyon token via Pairing/RefreshPhoneRelay using
     * the persisted ECDSA RefreshKey. Returns the new token + TTL.
     * Called periodically by the session manager before the current
     * TTL expires.
     */
    // refreshTachyonToken removed in v1 — the real refresh path uses
    // the separate `Registration.RegisterRefresh` endpoint (not
    // Pairing.RefreshPhoneRelay, which only regenerates a fresh QR for
    // re-pairing). Token TTL is several days; for v1 we surface a
    // re-pair prompt if the user gets logged out.

    /**
     * Refresh the QR code by calling RefreshPhoneRelay. Returns a new
     * QR URL. Port of Go's `Client.RefreshPhoneRelay` / `QRLoginProcess.Wait`.
     */
    suspend fun refreshPhoneRelay(
        rpc: RpcClient,
        auth: AuthData,
    ): RegisterResult {
        val pubKey = auth.refreshKey.publicKeyDer()
        val tachyonToken = auth.tachyonToken() ?: error("no tachyon token for QR refresh")
        val req = AuthenticationContainer.newBuilder()
            .setAuthMessage(
                AuthMessage.newBuilder()
                    .setRequestID(UUID.randomUUID().toString())
                    .setTachyonAuthToken(com.google.protobuf.ByteString.copyFrom(tachyonToken))
                    .setNetwork(QrNetwork)
                    .setConfigVersion(ConfigVersion)
            )
            .setBrowserDetails(BrowserDetails)
            .build()

        Log.i(TAG, "RefreshPhoneRelay …")
        val resp = rpc.postProtobuf(
            url = Endpoints.RefreshPhoneRelayUrl,
            body = req,
            responseTemplate = RegisterPhoneRelayResponse.getDefaultInstance(),
        )
        Log.i(TAG, "RefreshPhoneRelay OK")

        val urlData = URLData.newBuilder()
            .setPairingKey(resp.pairingKey)
            .setAESKey(auth.crypto().aesKey.toByteString())
            .setHMACKey(auth.crypto().hmacKey.toByteString())
            .build()
        val qrPayload = Base64.encodeToString(urlData.toByteArray(), Base64.NO_WRAP)
        val qrUrl = Endpoints.QrCodeBaseUrl + qrPayload

        return RegisterResult(
            tachyonToken = resp.authKeyData.tachyonAuthToken.toByteArray(),
            tachyonTtlUs = resp.authKeyData.ttl,
            qrUrl = qrUrl,
        )
    }
}
