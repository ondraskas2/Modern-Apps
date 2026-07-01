package com.vayunmathur.messages.meta

import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetaMqttClient(
    private val authData: MetaAuthData,
    private val config: MetaConfig = MetaConfig(),
) {
    private companion object {
        const val TAG = "MetaMqttClient"
        const val PING_INTERVAL_MS = 10000L
        const val PONG_TIMEOUT_MS = 30000L
        const val INITIAL_RECONNECT_DELAY_MS = 1000L
        const val MAX_RECONNECT_DELAY_MS = 60000L
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val ACK_TIMEOUT_MS = 30000L
        const val ERROR_24_COOLDOWN_MS = 10 * 60 * 1000L

        // Thread sync groups to page through on initial backfill: 1 = primary inbox (MailBox),
        // 95 = general/other. Mirrors the Go bridge ready-event FetchThreadsTask fan-out.
        val THREAD_SYNC_GROUPS = listOf(1, 95)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val writeMutex = Mutex()
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var reconnectAttempts = 0
    private var lastFullReconnectTime = 0L
    private var lastError24ReconnectTime = 0L

    private val packetsSent = AtomicInteger(0)
    private val sessionId = MetaProtocol.generateSessionId()

    // Pending ACK channels
    private val pubAckChannels = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val subAckChannels = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
    private val requestChannels = ConcurrentHashMap<Int, CompletableDeferred<MetaProtocol.MqttMessage>>()

    private var previouslyConnected = false
    // Web bootstrap (#5): versionId/appId/broker come from MetaConfig when the
    // page parse succeeded, otherwise fall back to the previous hardcoded values.
    var versionId: Long = config.versionId
    var appId: String = config.defaultAppId(authData.platform).toString()

    // DB SyncManager (#10).
    private val syncManager = MetaSyncManager(
        platform = authData.platform,
        versionId = config.versionId,
        syncParamsMailbox = config.syncParamsMailbox,
        syncParamsContact = config.syncParamsContact,
        syncParamsE2ee = config.syncParamsE2ee,
    )

    private val _messages = MutableSharedFlow<MetaProtocol.MqttMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<MetaProtocol.MqttMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16, replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    fun safePacketId(): Int {
        while (true) {
            val id = packetsSent.incrementAndGet() and 0xFFFF
            if (id != 0) return id
        }
    }

    fun connect() {
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
        }

        val mqttUrl = buildBrokerUrl()

        val request = Request.Builder()
            .url(mqttUrl)
            .header("Cookie", authData.toCookieHeader())
            .header("Origin", when (authData.platform) {
                MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_BASE_URL
                MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_BASE_URL
            })
            .header("User-Agent", MetaProtocol.USER_AGENT)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected for ${authData.platform}")
                scope.launch {
                    try {
                        sendConnectPacket()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send CONNECT packet", e)
                        _connectionState.emit(ConnectionState.Disconnected("Connect failed: ${e.message}"))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.w(TAG, "Unexpected text message in websocket")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                cancelAllPending()
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Closed: $reason"))
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                cancelAllPending()
                // Issue #4: Detect HTTP auth/consent errors from WebSocket handshake failure
                val statusCode = response?.code
                val reason = when {
                    statusCode == 401 || statusCode == 403 -> "TokenExpired"
                    t.message?.contains("consent", ignoreCase = true) == true -> "ConsentRequired"
                    else -> "Failure: ${t.message}"
                }
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected(reason))
                }
                if (reason != "TokenExpired" && reason != "ConsentRequired") {
                    scheduleReconnect()
                }
            }
        })
    }

    fun disconnect() {
        pingJob?.cancel()
        pongTimeoutJob?.cancel()
        reconnectJob?.cancel()
        cancelAllPending()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private fun buildBrokerUrl(): String {
        val baseUrl = config.broker ?: when (authData.platform) {
            MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_MQTT_URL
            MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_MQTT_URL
        }
        val sep = if (baseUrl.endsWith("?") || baseUrl.endsWith("&")) "" else if (baseUrl.contains("?")) "&" else "?"
        val cid = authData.cookies["cid"] ?: java.util.UUID.randomUUID().toString()
        return "${baseUrl}${sep}sid=$sessionId&cid=$cid"
    }

    private suspend fun sendData(data: ByteArray): Boolean {
        writeMutex.withLock {
            val ws = webSocket ?: return false
            return ws.send(ByteString.of(*data))
        }
    }

    private suspend fun sendConnectPacket() {
        val connectJsonStr = MetaProtocol.buildConnectJson(
            accountId = authData.userId,
            sessionId = sessionId,
            appId = authData.cookies["appId"]?.toLongOrNull()
                ?: config.defaultAppId(authData.platform),
            cid = authData.cookies["cid"] ?: java.util.UUID.randomUUID().toString(),
            platform = authData.platform,
            previouslyConnected = previouslyConnected,
            versionId = versionId,
        )

        val connectPacket = MqttFraming.buildConnectPacket(connectJsonStr)
        sendData(connectPacket)
    }

    private fun handleBinaryMessage(data: ByteArray) {
        val response = MqttFraming.parseResponse(data) ?: return

        // Any inbound traffic resets pong timeout
        resetPongTimeout()

        when (response) {
            is MqttFraming.MqttResponse.ConnAck -> handleConnAck(response)
            is MqttFraming.MqttResponse.PubAck -> handlePubAck(response)
            is MqttFraming.MqttResponse.SubAck -> handleSubAck(response)
            is MqttFraming.MqttResponse.PublishMessage -> handlePublishMessage(response)
            is MqttFraming.MqttResponse.PingResp -> {
                Log.d(TAG, "Got ping response")
            }
        }
    }

    private fun handleConnAck(connAck: MqttFraming.MqttResponse.ConnAck) {
        if (connAck.connectionCode != MetaProtocol.CONNECTION_ACCEPTED) {
            Log.e(TAG, "Connection refused: code=${connAck.connectionCode}")
            val reason = when (connAck.connectionCode) {
                MetaProtocol.CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD,
                MetaProtocol.CONNECTION_REFUSED_UNAUTHORIZED -> "TokenExpired"
                MetaProtocol.CONNECTION_REFUSED_SERVER_UNAVAILABLE -> "ServerUnavailable"
                MetaProtocol.CONNECTION_REFUSED_IDENTIFIER_REJECTED -> "IdentifierRejected"
                MetaProtocol.CONNECTION_REFUSED_UNKNOWN_24 -> "UnknownError24"
                else -> "Connection refused: ${connAck.connectionCode}"
            }
            scope.launch {
                _connectionState.emit(ConnectionState.Disconnected(reason))
            }
            when (connAck.connectionCode) {
                MetaProtocol.CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD,
                MetaProtocol.CONNECTION_REFUSED_UNAUTHORIZED -> {
                    // Bad credentials — don't reconnect
                }
                MetaProtocol.CONNECTION_REFUSED_SERVER_UNAVAILABLE -> {
                    // Go bridge: attempt full reconnect; for IG fall back to challenge required
                    val now = System.currentTimeMillis()
                    if (now - lastFullReconnectTime > 60_000) {
                        lastFullReconnectTime = now
                        scheduleReconnect()
                    } else if (authData.platform == MetaAuthData.Platform.INSTAGRAM) {
                        scope.launch {
                            _connectionState.emit(ConnectionState.Disconnected("IGChallengeRequiredMaybe"))
                        }
                    } else {
                        scheduleReconnect()
                    }
                }
                MetaProtocol.CONNECTION_REFUSED_UNKNOWN_24 -> {
                    // Go bridge: attempt full reconnect with 10-minute cooldown
                    val now = System.currentTimeMillis()
                    if (now - lastError24ReconnectTime > ERROR_24_COOLDOWN_MS) {
                        lastError24ReconnectTime = now
                        scheduleReconnect()
                    } else {
                        Log.w(TAG, "Last reconnect for code 24 was too recent, not reconnecting")
                    }
                }
                else -> scheduleReconnect()
            }
            return
        }

        Log.i(TAG, "CONNACK received, connection accepted")
        scope.launch {
            try {
                handleReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle ready event", e)
                _connectionState.emit(ConnectionState.Disconnected("Ready failed: ${e.message}"))
            }
        }
    }

    private suspend fun handleReady() {
        reconnectAttempts = 0
        if (previouslyConnected) {
            // #10: on reconnect the Go bridge re-syncs the minimal DB set and
            // emits a Reconnected event (events.go handleReadyEvent). Mirror the
            // re-sync so cursors stay current after a drop.
            _connectionState.emit(ConnectionState.Connected)
            startPing()
            runReconnectSync()
            return
        }

        // Send app settings (from messagix/events.go handleReadyEvent)
        val appSettingsJson = MetaProtocol.buildAppSettingsJson(versionId)
        val packetId = safePacketId()
        sendPublishPacket(MetaProtocol.TOPIC_LS_APP_SETTINGS, appSettingsJson, packetId)

        // Subscribe to required topics (from messagix/events.go handleReadyEvent + Go bridge)
        sendSubscribePacket(MetaProtocol.TOPIC_LS_FOREGROUND_STATE, MqttPackets.QOS_LEVEL_0)
        sendSubscribePacket(MetaProtocol.TOPIC_LS_RESP, MqttPackets.QOS_LEVEL_0)
        sendSubscribePacket("/t_ms", MqttPackets.QOS_LEVEL_0)
        sendSubscribePacket(MetaProtocol.TOPIC_THREAD_TYPING, MqttPackets.QOS_LEVEL_0)
        sendSubscribePacket(MetaProtocol.TOPIC_ORCA_TYPING_NOTIFICATIONS, MqttPackets.QOS_LEVEL_0)
        sendSubscribePacket("/orca_presence", MqttPackets.QOS_LEVEL_0)

        _connectionState.emit(ConnectionState.Connected)
        startPing()

        // Fetch threads for SyncGroup 1 and SyncGroup 95 for each known parent
        // thread key (#5: ParentThreadKeys from bootstrap). handleReady relies on
        // these responses being processed, so since the automatic emit is now
        // suppressed for request-correlated responses (#10) we re-inject them.
        val ptks = config.parentThreadKeys.ifEmpty { listOf(-1L) }
        for (tk in ptks) {
            val fetchSG1 = MetaProtocol.buildFetchThreadsPayload(versionId, syncGroup = 1, parentThreadKey = tk)
            makeLSRequest(fetchSG1, MetaProtocol.LS_REQUEST_TYPE_TASK)?.let { emitForProcessing(it) }

            val fetchSG95 = MetaProtocol.buildFetchThreadsPayload(versionId, syncGroup = 95, parentThreadKey = tk)
            makeLSRequest(fetchSG95, MetaProtocol.LS_REQUEST_TYPE_TASK)?.let { emitForProcessing(it) }
        }

        // Report app state as FOREGROUND (from messagix/events.go)
        val reportPayload = MetaProtocol.buildReportAppStatePayload(versionId)
        makeLSRequest(reportPayload, MetaProtocol.LS_REQUEST_TYPE_TASK)

        // #10: initial DB sync via the SyncManager.
        runInitialSync()

        previouslyConnected = true
    }

    // The SyncManager performs /ls_req round-trips through makeLSRequest, then we
    // re-inject each response so its events are processed exactly once.
    private val syncRequester = MetaSyncManager.LSRequester { payload, type ->
        val response = makeLSRequest(payload, type)
        if (response != null) emitForProcessing(response)
        response
    }

    private suspend fun runInitialSync() {
        try {
            syncManager.versionId = versionId
            syncManager.ensureSynced(syncManager.initialSyncSet(), syncRequester)
        } catch (e: Exception) {
            Log.e(TAG, "Initial DB sync failed", e)
        }
    }

    private suspend fun runReconnectSync() {
        try {
            syncManager.versionId = versionId
            syncManager.ensureSynced(syncManager.reconnectSyncSet(), syncRequester)
        } catch (e: Exception) {
            Log.e(TAG, "Reconnect DB sync failed", e)
        }
    }

    private fun handlePubAck(pubAck: MqttFraming.MqttResponse.PubAck) {
        pubAckChannels.remove(pubAck.packetId)?.complete(Unit)
    }

    private fun handleSubAck(subAck: MqttFraming.MqttResponse.SubAck) {
        subAckChannels.remove(subAck.packetId)?.complete(subAck.qosLevel)
    }

    private fun handlePublishMessage(publish: MqttFraming.MqttResponse.PublishMessage) {
        if (publish.qos == MqttPackets.QOS_LEVEL_1.toInt() && publish.packetId > 0) {
            scope.launch {
                sendData(MqttFraming.buildPubAckPacket(publish.packetId))
            }
        } else if (publish.qos == MqttPackets.QOS_LEVEL_2.toInt()) {
            Log.e(TAG, "Got packet with QoS level 2")
        }

        val mqttMessage = MetaProtocol.MqttMessage(
            topic = publish.topic,
            payload = publish.payload,
            packetId = publish.packetId,
            qos = publish.qos,
        )

        // #10: stop double-emitting request-correlated /ls_resp responses. In the
        // Go bridge (events.go handlePublishResponseEvent) a response with a
        // non-zero request_id that matches a pending request is delivered ONLY to
        // the waiter — it is not re-emitted as a server event. We mirror that
        // here: if a waiter is registered we complete it and suppress the emit.
        // Callers that need the response's events processed (thread fetches, DB
        // syncs) re-inject it via emitForProcessing().
        val responseData = MetaProtocol.parsePublishResponse(publish.payload)
        if (responseData != null && responseData.requestId > 0) {
            val requestIdInt = responseData.requestId.toInt()
            val waiter = requestChannels.remove(requestIdInt)
            if (waiter != null) {
                waiter.complete(mqttMessage)
                return
            }
        }

        // Server-initiated message (request_id == 0 or no waiter): emit for
        // downstream processing.
        scope.launch {
            _messages.emit(mqttMessage)
        }
    }

    /**
     * Re-injects a response into the message pipeline so its decoded events are
     * processed exactly once. Used for request-correlated responses (thread
     * fetches / DB syncs) whose automatic emit is suppressed by [handlePublishMessage].
     */
    suspend fun emitForProcessing(message: MetaProtocol.MqttMessage) {
        _messages.emit(message)
    }

    /**
     * Fetch the FULL DM thread list after connect, following pagination so every conversation
     * (not just the first page) syncs into the DB. Mirrors the Go bridge's ready-event
     * FetchThreadsTask fan-out (events.go) + StartThreadBackfill/FetchMoreThreads
     * (threadbackfill.go): for each parent thread key and each thread sync group (1 = primary
     * inbox, 95 = general/other), page backwards using the LSUpsertSyncGroupThreadsRange cursor
     * (minThreadKey / minLastActivityTimestampMs) until hasMoreBefore is false or the cursor stops
     * advancing. Each response is re-injected via [emitForProcessing] so its threads + messages go
     * through the normal event → DB path.
     *
     * NOTE: Instagram message-requests ("pending" folder) are served by a separate GraphQL query
     * (IGListMessageRequests) in the Go bridge, not a socket task, so they are not covered here —
     * see the task #26 report for that follow-up.
     */
    suspend fun backfillThreads(maxPagesPerGroup: Int = 30) {
        val parentKeys = config.parentThreadKeys.ifEmpty { listOf(-1L) }
        for (syncGroup in THREAD_SYNC_GROUPS) {
            for (parentThreadKey in parentKeys) {
                paginateThreadGroup(syncGroup, parentThreadKey, maxPagesPerGroup)
            }
        }
    }

    private suspend fun paginateThreadGroup(syncGroup: Int, parentThreadKey: Long, maxPages: Int) {
        var referenceThreadKey = 0L
        var referenceActivityTimestamp = 9999999999999L
        var prevMinThreadKey = Long.MIN_VALUE
        var page = 0
        while (page++ < maxPages) {
            val payload = MetaProtocol.buildFetchThreadsPayload(
                versionId = versionId,
                syncGroup = syncGroup,
                parentThreadKey = parentThreadKey,
                referenceThreadKey = referenceThreadKey,
                referenceActivityTimestamp = referenceActivityTimestamp,
            )
            val response = makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) ?: break
            emitForProcessing(response)

            // Decode the same response to read the pagination cursor for this sync group.
            val responseData = MetaProtocol.parsePublishResponse(response.payload) ?: break
            val events = LightspeedDecoder.decodePublishResponse(responseData.payload, responseData.sp)
            val range = MetaProtocol.parseSyncGroupRanges(events)
                .firstOrNull { it.syncGroup == syncGroup.toLong() } ?: break

            if (!range.hasMoreBefore) break
            // Guard against a stuck cursor (hasMoreBefore may never flip false on the server).
            if (range.minThreadKey == prevMinThreadKey) break
            prevMinThreadKey = range.minThreadKey
            referenceThreadKey = range.minThreadKey
            referenceActivityTimestamp = range.minLastActivityTimestampMs
        }
    }

    suspend fun sendPublishPacket(
        topic: String,
        jsonData: String,
        packetId: Int = safePacketId(),
        qos: Byte = MqttPackets.QOS_LEVEL_1,
    ): Int {
        val packet = MqttFraming.buildPublishPacket(topic, jsonData, qos, packetId)

        if (qos > 0) {
            val ackDeferred = CompletableDeferred<Unit>()
            pubAckChannels[packetId] = ackDeferred
        }

        if (!sendData(packet)) {
            pubAckChannels.remove(packetId)
            return -1
        }

        if (qos > 0) {
            try {
                withTimeout(ACK_TIMEOUT_MS) {
                    pubAckChannels[packetId]?.await()
                }
            } catch (e: Exception) {
                pubAckChannels.remove(packetId)
                Log.w(TAG, "Timeout waiting for PUBACK for packet $packetId")
            }
        }

        return packetId
    }

    private suspend fun sendSubscribePacket(topic: String, qos: Byte): Boolean {
        val packetId = safePacketId()
        val packet = MqttFraming.buildSubscribePacket(topic, qos, packetId)
        val ackDeferred = CompletableDeferred<Int>()
        subAckChannels[packetId] = ackDeferred
        if (!sendData(packet)) {
            subAckChannels.remove(packetId)
            return false
        }
        try {
            withTimeout(ACK_TIMEOUT_MS) {
                ackDeferred.await()
            }
        } catch (e: Exception) {
            subAckChannels.remove(packetId)
            Log.w(TAG, "Timeout waiting for SUBACK for topic $topic")
        }
        return true
    }

    suspend fun makeLSRequest(payload: String, type: Int): MetaProtocol.MqttMessage? {
        val packetId = safePacketId()
        val lsRequestJson = MetaProtocol.buildLSRequestJson(
            appId = appId,
            payload = payload,
            requestId = packetId,
            type = type,
        )

        val responseDeferred = CompletableDeferred<MetaProtocol.MqttMessage>()
        requestChannels[packetId] = responseDeferred

        val sentId = sendPublishPacket(MetaProtocol.TOPIC_LS_REQ, lsRequestJson, packetId)
        if (sentId < 0) {
            requestChannels.remove(packetId)
            return null
        }

        // STATELESS type doesn't expect a response — return a synthetic success
        if (type == MetaProtocol.LS_REQUEST_TYPE_STATELESS) {
            requestChannels.remove(packetId)
            return MetaProtocol.MqttMessage(
                topic = MetaProtocol.TOPIC_LS_RESP,
                payload = ByteArray(0),
            )
        }

        return try {
            withTimeout(ACK_TIMEOUT_MS) {
                responseDeferred.await()
            }
        } catch (e: Exception) {
            requestChannels.remove(packetId)
            Log.w(TAG, "Timeout waiting for LS response for request $packetId")
            null
        }
    }

    suspend fun publish(topic: String, payload: ByteArray): Boolean {
        val jsonData = String(payload, Charsets.UTF_8)
        return sendPublishPacket(topic, jsonData) >= 0
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                val sent = sendData(MqttFraming.buildPingReqPacket())
                if (!sent) {
                    Log.e(TAG, "Failed to send ping")
                    break
                }
            }
        }
        resetPongTimeout()
    }

    private fun resetPongTimeout() {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = scope.launch {
            delay(PONG_TIMEOUT_MS)
            Log.e(TAG, "Pong timeout")
            disconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached for ${authData.platform}")
            scope.launch {
                _connectionState.emit(ConnectionState.Disconnected("Max reconnect attempts reached"))
            }
            return
        }
        val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1).coerceAtMost(6)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms for ${authData.platform}")
            delay(delayMs)
            connect()
        }
    }

    private fun cancelAllPending() {
        val cancelError = Exception("Connection closed")
        pubAckChannels.values.forEach { it.completeExceptionally(cancelError) }
        pubAckChannels.clear()
        subAckChannels.values.forEach { it.completeExceptionally(cancelError) }
        subAckChannels.clear()
        requestChannels.values.forEach { it.completeExceptionally(cancelError) }
        requestChannels.clear()
    }
}
