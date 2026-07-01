package com.vayunmathur.messages.signal.web

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketResponseMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SignalHttpClient {
    private const val TAG = "SignalHttp"

    const val API_HOST = "chat.signal.org"
    const val STORAGE_HOST = "storage.signal.org"
    const val CDN1_HOST = "cdn.signal.org"
    const val CDN2_HOST = "cdn2.signal.org"
    const val CDN3_HOST = "cdn3.signal.org"

    val CDN_HOSTS = listOf(CDN1_HOST, CDN1_HOST, CDN2_HOST, CDN3_HOST)

    // Must match the Signal bridge's User-Agent format. Signal-Server inspects this
    // during device linking and refuses to add a device that identifies as a mobile
    // primary platform (e.g. an "android" UA) as a *linked* device, returning a bare
    // 409 Conflict. The bridge uses "signalmeow/0.1.0 libsignal/<ver> go/<ver>".
    const val USER_AGENT = "signalmeow/0.1.0 libsignal/0.86.5"
    const val SIGNAL_AGENT = "MAU"

    const val CONTENT_TYPE_JSON = "application/json"
    const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
    const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"
    const val CONTENT_TYPE_OFFSET_OCTET_STREAM = "application/offset+octet-stream"
    const val CONTENT_TYPE_MULTI_RECIPIENT_MESSAGE = "application/vnd.signal-messenger.mrm"

    private lateinit var client: OkHttpClient

    private var initialized = false
    private var httpReqCounter = 0

    fun init(context: Context) {
        if (initialized) return
        val (sslSocketFactory, trustManager) = CertPinning.createSslSocketFactory(context)
        client = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .build()
        initialized = true
    }

    suspend fun request(
        host: String = API_HOST,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = CONTENT_TYPE_JSON,
        username: String? = null,
        password: String? = null,
        headers: Map<String, String> = emptyMap(),
        overrideUrl: String? = null,
    ): Response {
        val normalizedPath = if (path.isNotEmpty() && !path.startsWith("/")) "/$path" else path
        val url = overrideUrl ?: "https://$host$normalizedPath"

        val requestBody = body?.toRequestBody(contentType.toMediaType())

        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Content-Type", if (contentType.isNotEmpty()) contentType else CONTENT_TYPE_JSON)
            .header("Content-Length", (body?.size ?: 0).toString())
            .header("User-Agent", USER_AGENT)
            .header("X-Signal-Agent", SIGNAL_AGENT)
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .apply {
                if (username != null && password != null) {
                    header("Authorization", okhttp3.Credentials.basic(username, password))
                }
            }
            .build()

        httpReqCounter++
        Log.d(TAG, "[$httpReqCounter] $method $url")

        val startTime = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    val dur = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[$httpReqCounter] status=${response.code} duration=${dur}ms")
                    cont.resume(response)
                }
            })
        }
    }

    suspend fun getAttachment(path: String, cdnNumber: Int): Response {
        val host = if (cdnNumber == 0) {
            CDN_HOSTS[0]
        } else if (cdnNumber > 0 && cdnNumber < CDN_HOSTS.size) {
            CDN_HOSTS[cdnNumber]
        } else {
            Log.w(TAG, "Invalid CDN index $cdnNumber")
            CDN_HOSTS[0]
        }

        val url = "https://$host$path"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Content-Type", CONTENT_TYPE_OCTET_STREAM)
            .header("User-Agent", USER_AGENT)
            .build()

        httpReqCounter++
        Log.d(TAG, "[$httpReqCounter] GET attachment $url")

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    cont.resume(response)
                }
            })
        }
    }

    fun decodeHttpResponseBody(response: Response): ByteArray {
        response.use { resp ->
            if (resp.code !in 200..299) {
                val body = resp.body?.string() ?: ""
                Log.d(TAG, "Unexpected status code: ${resp.code}, body: $body")
                throw IOException("Unexpected status code: ${resp.code} ${resp.message}")
            }
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    fun decodeWsResponseBody(response: WebSocketResponseMessage?): ByteArray? {
        if (response == null) return null
        if (response.status < 200 || response.status >= 300) {
            Log.w(TAG, "Unexpected WS status=${response.status} message=${response.message} headers=${response.headersList} body=${response.body?.toByteArray()?.decodeToString()}")
            throw IOException("Unexpected response status ${response.status}")
        }
        return response.body?.toByteArray() ?: ByteArray(0)
    }

    fun cdnHost(cdnNumber: Int): String {
        return if (cdnNumber == 0) {
            CDN_HOSTS[0]
        } else if (cdnNumber > 0 && cdnNumber < CDN_HOSTS.size) {
            CDN_HOSTS[cdnNumber]
        } else {
            Log.w(TAG, "Invalid CDN index $cdnNumber")
            CDN_HOSTS[0]
        }
    }
}
