package com.vayunmathur.messages.signal.web

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketMessage
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketRequestMessage
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketResponseMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class SignalWebSocket(
    private val context: Context,
    private val basicAuth: String? = null,
) {
    sealed class ConnectionEvent {
        object Connecting : ConnectionEvent()
        object Connected : ConnectionEvent()
        data class Disconnected(val reason: String) : ConnectionEvent()
        object LoggedOut : ConnectionEvent()
        data class Error(val reason: String) : ConnectionEvent()
        data class FatalError(val reason: String) : ConnectionEvent()
        object CleanShutdown : ConnectionEvent()
    }

    companion object {
        const val TAG = "SignalWebSocket"
        const val WEBSOCKET_PATH = "/v1/websocket/"
        const val WEBSOCKET_PROVISIONING_PATH = "/v1/websocket/provisioning/"
        const val PING_INTERVAL_MS = 30_000L
        const val PING_TIMEOUT_MS = 20_000L
        const val PING_TIMEOUT_LIMIT = 5
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 150_000L
        const val MAX_REQUEST_RETRIES = 3
        const val ERROR_COUNT_LIMIT = 500

        fun createWsRequest(
            method: String,
            path: String,
            body: ByteArray? = null,
            username: String? = null,
            password: String? = null,
        ): WebSocketRequestMessage {
            val builder = WebSocketRequestMessage.newBuilder()
                .setVerb(method)
                .setPath(path)

            if (body != null) {
                builder.setBody(com.google.protobuf.ByteString.copyFrom(body))
            }

            builder.addHeaders("content-type:application/json; charset=utf-8")

            if (username != null && password != null) {
                val encoded = android.util.Base64.encodeToString(
                    "$username:$password".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                builder.addHeaders("authorization:Basic $encoded")
            }

            return builder.build()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<WebSocketResponseMessage>>()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String? = null
    private var currentBackoff = INITIAL_BACKOFF_MS
    private var reconnectCount = 0
    private var shouldReconnect = false
    private var consecutivePingFailures = 0
    private var errorCount = 0
    @Volatile
    private var forceReconnectRequested = false

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(replay = 1)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    var isConnected: Boolean = false
        private set

    var incomingRequestHandler: ((WebSocketRequestMessage) -> Unit)? = null

    private val client: OkHttpClient by lazy {
        val (sslSocketFactory, trustManager) = CertPinning.createSslSocketFactory(context)
        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected")
        isConnected = true
            currentBackoff = INITIAL_BACKOFF_MS
            reconnectCount = 0
            resetPingState()
            scope.launch { _connectionEvents.emit(ConnectionEvent.Connected) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val message = WebSocketMessage.parseFrom(bytes.toByteArray())
                when (message.type) {
                    WebSocketMessage.Type.RESPONSE -> handleResponse(message.response)
                    WebSocketMessage.Type.REQUEST -> handleRequest(message.request)
                    WebSocketMessage.Type.UNKNOWN ->
                        Log.e(TAG, "Received message with UNKNOWN type")
                    else -> Log.w(TAG, "Unknown message type: ${message.type}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed: $code $reason")
            onDisconnected(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Failure: ${t.message}")
            errorCount++
            if (errorCount > ERROR_COUNT_LIMIT) {
                Log.e(TAG, "Error count limit reached ($errorCount), fatal")
                shouldReconnect = false
                scope.launch { _connectionEvents.emit(ConnectionEvent.FatalError("Too many errors")) }
                onDisconnected("Too many errors")
                return
            }
            if (response?.code == 403) {
                shouldReconnect = false
                onDisconnected("Logged out")
                scope.launch { _connectionEvents.emit(ConnectionEvent.LoggedOut) }
                return
            }
            if (response != null && response.code > 0 && response.code < 500) {
                shouldReconnect = false
                scope.launch { _connectionEvents.emit(ConnectionEvent.FatalError("Unexpected status: ${response.code}")) }
                onDisconnected("Unexpected status: ${response.code}")
                return
            }
            if (response != null && response.code in 500..599) {
                scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected("Server error: ${response.code}")) }
            } else if (currentBackoff < MAX_BACKOFF_MS) {
                scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected("Transient error: ${t.message ?: "Unknown error"}")) }
            } else {
                scope.launch { _connectionEvents.emit(ConnectionEvent.Error("Continuing error: ${t.message ?: "Unknown error"}")) }
            }
            onDisconnected(t.message ?: "Unknown error")
        }
    }

    fun connect(url: String, autoReconnect: Boolean = true) {
        currentUrl = url
        shouldReconnect = autoReconnect
        openSocket(url)
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "")
        webSocket = null
        isConnected = false
        failAllPending("Disconnected")
        scope.launch { _connectionEvents.emit(ConnectionEvent.CleanShutdown) }
    }

    fun forceReconnect() {
        forceReconnectRequested = true
        webSocket?.cancel()
    }

    suspend fun sendRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebSocketResponseMessage {
        val isSelfDelete = method == "DELETE" && path.startsWith("/v1/devices/")

        var lastException: Exception? = null
        for (attempt in 0 until MAX_REQUEST_RETRIES) {
            try {
                return sendRequestOnce(method, path, body, headers)
            } catch (e: IOException) {
                lastException = e
                if (isSelfDelete) {
                    throw e
                }
                if (e.message?.contains("Took too long") == true) {
                    throw e
                }
                Log.w(TAG, "Received nil response, retrying (attempt ${attempt + 1}/$MAX_REQUEST_RETRIES): ${e.message}")
            }
        }
        throw lastException ?: IOException("Retried $MAX_REQUEST_RETRIES times, giving up")
    }

    private suspend fun sendRequestOnce(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebSocketResponseMessage {
        val id = requestId.getAndIncrement()
        val deferred = CompletableDeferred<WebSocketResponseMessage>()
        pendingRequests[id] = deferred

        val request = WebSocketRequestMessage.newBuilder()
            .setId(id)
            .setVerb(method)
            .setPath(path)
            .apply {
                if (body != null) {
                    setBody(com.google.protobuf.ByteString.copyFrom(body))
                }
                var hasContentType = false
                headers.forEach { (k, v) ->
                    if (k.lowercase() == "content-type") hasContentType = true
                    addHeaders("${k.lowercase()}:$v")
                }
                if (!hasContentType && body != null) {
                    addHeaders("content-type:application/json")
                }
                if (basicAuth != null) {
                    addHeaders("authorization:Basic $basicAuth")
                }
            }
            .build()

        val message = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(request)
            .build()

        val sent = webSocket?.send(message.toByteArray().toByteString()) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            throw IOException("WebSocket send failed")
        }

        return deferred.await()
    }

    fun sendResponse(requestId: Long, status: Int) {
        if (status != 200 && status != 400) {
            throw IllegalArgumentException("Unsupported response status: $status")
        }
        val msg = if (status == 200) "OK" else "Unknown"

        val response = WebSocketResponseMessage.newBuilder()
            .setId(requestId)
            .setStatus(status)
            .setMessage(msg)
            .addAllHeaders(emptyList())
            .build()

        val wsMsg = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()

        webSocket?.send(wsMsg.toByteArray().toByteString())
    }

    private fun openSocket(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SignalHttpClient.USER_AGENT)
            .header("X-Signal-Agent", SignalHttpClient.SIGNAL_AGENT)
            .apply {
                if (basicAuth != null) {
                    header("Authorization", "Basic $basicAuth")
                }
            }
            .build()

        webSocket = client.newWebSocket(request, listener)
    }

    private fun handleResponse(response: WebSocketResponseMessage) {
        val deferred = pendingRequests.remove(response.id)
        if (deferred != null) {
            deferred.complete(response)
        } else {
            Log.w(TAG, "No pending request for response id=${response.id}")
        }
    }

    private fun handleRequest(request: WebSocketRequestMessage) {
        val handler = incomingRequestHandler
            ?: throw IllegalStateException("Received request but no handler")
        handler(request)
    }

    private fun resetPingState() {
        consecutivePingFailures = 0
    }

    private fun onDisconnected(reason: String) {
        isConnected = false
        failAllPending(reason)
        scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected(reason)) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = currentUrl ?: return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (forceReconnectRequested) {
                forceReconnectRequested = false
                currentBackoff = INITIAL_BACKOFF_MS
                reconnectCount = 0
            } else {
                // Exponential backoff matching Go: 2 << retryCount, max 150s
                currentBackoff = ((2L shl reconnectCount) * 1000L).coerceAtMost(MAX_BACKOFF_MS)
                reconnectCount++
                Log.d(TAG, "Reconnecting in ${currentBackoff}ms")
                delay(currentBackoff)
            }
            openSocket(url)
        }
    }

    private fun failAllPending(reason: String) {
        val error = IOException("WebSocket closed: $reason")
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }
}
