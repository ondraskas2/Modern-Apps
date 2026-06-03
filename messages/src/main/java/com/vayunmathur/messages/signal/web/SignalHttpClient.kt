package com.vayunmathur.messages.signal.web

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

    private lateinit var client: OkHttpClient

    private var initialized = false

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
        contentType: String = "application/json",
        username: String? = null,
        password: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val url = "https://$host$path"

        val requestBody = body?.toRequestBody(contentType.toMediaType())

        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .apply {
                if (username != null && password != null) {
                    header("Authorization", Credentials.basic(username, password))
                }
                headers.forEach { (k, v) -> header(k, v) }
            }
            .build()

        Log.d(TAG, "$method $url")

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

    fun cdnHost(cdnNumber: Int): String = when (cdnNumber) {
        1 -> CDN1_HOST
        2 -> CDN2_HOST
        3 -> CDN3_HOST
        else -> CDN1_HOST
    }
}
