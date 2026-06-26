package com.vayunmathur.messages.meta

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom

/**
 * Real media upload (#14). Replaces the base64-in-text hack with the actual
 * upload endpoints used by messagix:
 *
 *  - Messenger: multipart POST to /ajax/mercury/upload.php (mercury.go) which
 *    returns file metadata containing the attachment fbid.
 *  - Instagram: a resumable "rupload" octet-stream POST to
 *    rupload.facebook.com/messenger_image/ (instagram.go EditGroupAvatar
 *    pattern) which returns an RUploadResponse media_id.
 *
 * The returned id is used as attachment_fbids in the SendMediaTask.
 *
 * // UNVERIFIED: requires live cookies + bootstrap tokens (lsd/fb_dtsg) and a
 * // real network round-trip; the request shape mirrors the Go reference.
 */
object MetaMediaUpload {
    private const val TAG = "MetaMediaUpload"
    private const val IG_RUPLOAD_URL = "https://rupload.facebook.com/messenger_image/"
    private const val ANTI_JS_PREFIX = "for (;;);"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Uploads [bytes] and returns the attachment fbid to reference in a send
     * task, or null on failure.
     */
    suspend fun upload(
        authData: MetaAuthData,
        config: MetaConfig,
        threadId: Long,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
        httpClient: OkHttpClient,
    ): Long? = withContext(Dispatchers.IO) {
        try {
            when (authData.platform) {
                MetaAuthData.Platform.MESSENGER ->
                    uploadMercury(authData, config, threadId, bytes, mimeType, fileName, httpClient)
                MetaAuthData.Platform.INSTAGRAM ->
                    uploadRupload(authData, config, bytes, mimeType, httpClient)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Media upload failed for ${authData.platform}", e)
            null
        }
    }

    private fun uploadMercury(
        authData: MetaAuthData,
        config: MetaConfig,
        threadId: Long,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
        httpClient: OkHttpClient,
    ): Long? {
        val boundary = "----WebKitFormBoundary" + randomAlphaNum(16)
        val body = MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "farr",
                fileName ?: "attachment",
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .build()

        val query = buildMercuryQuery(authData, config)
        val url = MetaProtocol.MESSENGER_BASE_URL + "/ajax/mercury/upload.php?" + query
        val referer = MetaProtocol.MESSENGER_BASE_URL + "/t/" + threadId

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Cookie", authData.toCookieHeader())
            .header("User-Agent", MetaProtocol.USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", MetaProtocol.MESSENGER_BASE_URL)
            .header("Referer", referer)
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
        if (config.lsdToken.isNotEmpty()) requestBuilder.header("x-fb-lsd", config.lsdToken)

        val respBody = httpClient.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Mercury upload returned ${resp.code}")
                return null
            }
            resp.body?.string()
        } ?: return null

        val jsonStr = respBody.removePrefix(ANTI_JS_PREFIX)
        val root = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: return null
        val payload = root["payload"]?.let { it as? JsonObject } ?: return null
        val metadata = payload["metadata"] ?: return null
        return extractFbid(metadata)
    }

    private fun uploadRupload(
        authData: MetaAuthData,
        config: MetaConfig,
        bytes: ByteArray,
        mimeType: String,
        httpClient: OkHttpClient,
    ): Long? {
        val userId = authData.userId
        val entityId = "${userId}_0_${MetaProtocol.generateEpochId()}"
        val entityType = if (mimeType == "image/png") "image/png" else "image/jpeg"
        val url = IG_RUPLOAD_URL + entityId

        val body = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Cookie", authData.toCookieHeader())
            .header("User-Agent", MetaProtocol.USER_AGENT)
            .header("content-type", "application/octet-stream")
            .header("image_type", "FILE_ATTACHMENT")
            .header("x-entity-name", entityId)
            .header("x-entity-length", bytes.size.toString())
            .header("x-entity-type", entityType)
            .header("offset", "0")
            .header("priority", "u=6, i")
        authData.cookies["csrftoken"]?.let { requestBuilder.header("x-csrftoken", it) }
        authData.cookies["mid"]?.let { requestBuilder.header("x-mid", it) }
        if (config.appId != 0L) requestBuilder.header("x-ig-app-id", config.appId.toString())

        val respBody = httpClient.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "rupload returned ${resp.code}")
                return null
            }
            resp.body?.string()
        } ?: return null

        val root = runCatching { json.parseToJsonElement(respBody).jsonObject }.getOrNull() ?: return null
        val mediaId = root["media_id"]?.jsonPrimitive?.longOrNull
            ?: root["upload_id"]?.jsonPrimitive?.content?.toLongOrNull()
        if (mediaId == null || mediaId == 0L) {
            Log.w(TAG, "rupload response had no media id: $respBody")
            return null
        }
        return mediaId
    }

    // Mercury metadata is an array (images) or object keyed by "0" (videos),
    // each entry carrying *_id fields. Ref types/mercury.go GetFbId.
    private fun extractFbid(metadata: kotlinx.serialization.json.JsonElement): Long? {
        val entry: JsonObject? = when (metadata) {
            is JsonArray -> metadata.firstOrNull()?.let { it as? JsonObject }
            is JsonObject -> metadata["0"]?.let { it as? JsonObject }
            else -> null
        }
        if (entry == null) return null
        val keys = listOf("video_id", "audio_id", "image_id", "gif_id", "file_id", "fbid")
        for (k in keys) {
            val v = entry[k]?.jsonPrimitive ?: continue
            val id = v.longOrNull ?: v.content.toLongOrNull()
            if (id != null && id != 0L) return id
        }
        return null
    }

    private fun buildMercuryQuery(authData: MetaAuthData, config: MetaConfig): String {
        val params = LinkedHashMap<String, String>()
        params["__a"] = "1"
        if (authData.userId.isNotEmpty()) params["__user"] = authData.userId
        if (config.lsdToken.isNotEmpty()) params["lsd"] = config.lsdToken
        if (config.fbDtsg.isNotEmpty()) params["fb_dtsg"] = config.fbDtsg
        if (config.jazoest.isNotEmpty()) params["jazoest"] = config.jazoest
        return params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun randomAlphaNum(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) { append(chars[rnd.nextInt(chars.length)]) }
        }
    }
}
