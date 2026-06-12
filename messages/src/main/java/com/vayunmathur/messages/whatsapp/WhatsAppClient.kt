package com.vayunmathur.messages.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object WhatsAppClient {

    private const val TAG = "WhatsAppClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingQrScan(val qrData: String) : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.WHATSAPP

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private lateinit var appContext: Context
    private var authData: WhatsAppAuthData? = null
    // Use WebView-based WebSocket to bypass TLS fingerprinting
    // WhatsApp blocks non-browser TLS fingerprints (JA3). WebView uses Chromium's
    // network stack which is indistinguishable from Chrome browser.
    private var webSocket: WebViewWebSocket? = null
    private var db: WhatsAppDatabase? = null
    private var backfillJob: Job? = null
    private var qrJob: Job? = null

    private val nameCache = ConcurrentHashMap<String, String>()
    private val lidToPhoneMap = ConcurrentHashMap<String, String>()
    private val undecryptableTracker = ConcurrentHashMap<String, Int>()
    private val pendingMessageIDs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = WhatsAppDatabase.getDatabase(appContext)
        Log.i(TAG, "init")
        runBlocking {
            val auth = WhatsAppAuthData.load(appContext)
            if (auth != null) {
                authData = auth
                _state.value = State.Connecting
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = WhatsAppAuthData.load(appContext) ?: run {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            connect(auth)
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing WhatsApp session")
        backfillJob?.cancel()
        qrJob?.cancel()
        webSocket?.disconnect()
        webSocket = null
        nameCache.clear()
        scope.launch { WhatsAppAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    fun startProvisioning() {
        _state.value = State.Connecting
        qrJob?.cancel()
        qrJob = scope.launch {
            try {
                // 1. Generate real X25519 key pairs for Noise and identity
                val (noisePriv, noisePub) = WhatsAppProtocol.generateX25519KeyPair()
                val (identityPriv, identityPub) = WhatsAppProtocol.generateX25519KeyPair()
                val advSecretKey = ByteArray(32).apply { random.nextBytes(this) }
                
                // 2. Connect WebSocket and wait for Noise handshake to complete
                webSocket = WebViewWebSocket(appContext, null).apply {
                    val ws = this
                    scope.launch {
                        connectionState.collect { connState ->
                            when (connState) {
                                is WebViewWebSocket.ConnectionState.Connected -> {
                                    Log.i(TAG, "WebSocket connected, waiting for ref from server")
                                }
                                is WebViewWebSocket.ConnectionState.Disconnected -> {
                                    if (_state.value is State.AwaitingQrScan || _state.value is State.Connecting) {
                                        _state.value = State.Disconnected(connState.reason)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    
                    scope.launch {
                        messages.collect { data ->
                            handleProvisioningMessage(data, noisePriv, noisePub, identityPriv, identityPub, advSecretKey)
                        }
                    }
                    
                    connect()
                }
                
                // 3. Generate QR data with real keys
                // Go format: https://wa.me/settings/linked_devices#ref,noise,identity,adv,clientType
                // Use a placeholder ref until the server sends one via pair-device IQ
                val refBytes = ByteArray(16).apply { random.nextBytes(this) }
                val ref = Base64.encodeToString(refBytes, Base64.NO_WRAP)
                val qrData = "https://wa.me/settings/linked_devices#" + listOf(
                    ref,
                    Base64.encodeToString(noisePub, Base64.NO_WRAP),
                    Base64.encodeToString(identityPub, Base64.NO_WRAP),
                    Base64.encodeToString(advSecretKey, Base64.NO_WRAP),
                    "1" // PairClientChrome (from whatsmeow/pair-code.go)
                ).joinToString(",")
                _state.value = State.AwaitingQrScan(qrData)
                
                Log.i(TAG, "QR code generated, waiting for phone to scan")

            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed", e)
                _state.value = State.Disconnected("Provisioning failed: ${e.message}")
            }
        }
    }
    
    private fun handleProvisioningMessage(
        data: ByteArray,
        noisePriv: ByteArray,
        noisePub: ByteArray,
        identityPriv: ByteArray,
        identityPub: ByteArray,
        advSecretKey: ByteArray,
    ) {
        scope.launch {
            try {
                val node = WhatsAppProtocol.decodeNode(data)
                Log.d(TAG, "Provisioning message: tag=${node.tag}")
                
                if (node.tag == "success") {
                    val wid = node.attrs["wid"] ?: ""
                    val signedPreKP = WhatsAppProtocol.generateX25519KeyPair()
                    val auth = WhatsAppAuthData(
                        phoneNumber = wid.substringBefore("@"),
                        pushName = node.attrs["pushname"] ?: "User",
                        wid = wid,
                        noisePrivateKey = Base64.encodeToString(noisePriv, Base64.NO_WRAP),
                        noisePublicKey = Base64.encodeToString(noisePub, Base64.NO_WRAP),
                        identityPrivateKey = Base64.encodeToString(identityPriv, Base64.NO_WRAP),
                        identityPublicKey = Base64.encodeToString(identityPub, Base64.NO_WRAP),
                        registrationId = random.nextInt(),
                        signedPreKeyId = 1,
                        signedPreKeyPublic = Base64.encodeToString(signedPreKP.second, Base64.NO_WRAP),
                        signedPreKeyPrivate = Base64.encodeToString(signedPreKP.first, Base64.NO_WRAP),
                        signedPreKeySignature = Base64.encodeToString(ByteArray(64), Base64.NO_WRAP),
                        advSecretKey = Base64.encodeToString(advSecretKey, Base64.NO_WRAP),
                    )
                    WhatsAppAuthData.save(appContext, auth)
                    authData = auth
                    _state.value = State.Connected
                    kickoffBackfill()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle provisioning message", e)
            }
        }
    }

    private suspend fun connect(auth: WhatsAppAuthData) {
        _state.value = State.Connecting

        // Use WebView-based WebSocket to bypass TLS fingerprinting
        webSocket = WebViewWebSocket(appContext, auth).apply {
            scope.launch {
                connectionState.collect { state ->
                    when (state) {
                        is WebViewWebSocket.ConnectionState.Connected -> {
                            _state.value = State.Connected
                            kickoffBackfill()
                        }
                        is WebViewWebSocket.ConnectionState.Disconnected -> {
                            _state.value = State.Disconnected(state.reason)
                        }
                        else -> {}
                    }
                }
            }

            scope.launch {
                messages.collect { data ->
                    handleIncomingMessage(data)
                }
            }

            connect()
        }
    }

    private fun handleIncomingMessage(data: ByteArray) {
        scope.launch {
            try {
                val node = WhatsAppProtocol.decodeNode(data)

                // Ack messages, notifications, and receipts (whatsmeow/receipt.go)
                if (node.tag == "message" || node.tag == "notification" || node.tag == "receipt") {
                    val ack = WhatsAppProtocol.buildAck(
                        nodeClass = node.tag,
                        nodeId = node.attrs["id"] ?: "",
                        from = node.attrs["from"] ?: "",
                        participant = node.attrs["participant"],
                        recipient = node.attrs["recipient"],
                        type = if (node.tag != "message") node.attrs["type"] else null,
                    )
                    webSocket?.send(WhatsAppProtocol.encodeNode(ack))
                }

                // Handle receipts with per-sender batching (Go handleWAReceipt)
                if (node.tag == "receipt") {
                    handleReceipt(node)
                    return@launch
                }

                // Handle chat presence with media type differentiation (Go handleWAChatPresence)
                if (node.tag == "chatstate") {
                    handleChatPresence(node)
                    return@launch
                }

                // Handle notifications (group changes, mute/pin/archive)
                if (node.tag == "notification") {
                    handleNotification(node)
                    return@launch
                }

                if (node.tag != "message") return@launch

                // Check for undecryptable messages (Go handleWAUndecryptableMessage)
                val encNode = node.getChildByTag("enc")
                if (encNode?.data == null && node.attrs["type"] == "text") {
                    trackUndecryptable(node)
                    return@launch
                }

                val message = WhatsAppProtocol.parseMessage(node) ?: return@launch

                // Skip status broadcasts (Go handleWAMessage status@broadcast check)
                if (message.from.startsWith("status@broadcast")) {
                    Log.d(TAG, "Skipping status broadcast from ${message.participant}")
                    return@launch
                }

                // Pending message dedup (Go handleWAMessage pendingMessages check)
                if (pendingMessageIDs.remove(message.id)) {
                    Log.d(TAG, "Ignoring pending message ${message.id}")
                    return@launch
                }

                // LID routing (Go rerouteWAMessage)
                val sender = resolveJID(message.participant ?: message.from)

                // Handle revoke (message deletion) from Go handleWAMessage/revoke case
                if (message.isRevoke && message.revokeTargetId != null) {
                    _events.emit(GMEvent.MessageDeleted(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.revokeTargetId,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle edit from Go handleWAMessage/edit case
                if (message.isEdit && message.editTargetId != null) {
                    _events.emit(GMEvent.MessageEdited(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.editTargetId,
                        newBody = message.body,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle disappearing timer change (Go handleWAMessage/ephemeral case)
                if (message.disappearingTimer != null) {
                    _events.emit(GMEvent.IncomingMessage(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.id,
                        body = message.body,
                        peerName = resolveName(sender),
                        peerPhone = null,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle poll creation — store option hashes (Go handleWAMessage/poll case)
                if (message.pollData != null && !message.pollData.isPollVote) {
                    storePollOptions(message.id, message.pollData.options)
                }

                _events.emit(GMEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.id,
                    body = message.body,
                    peerName = resolveName(sender),
                    peerPhone = null,
                    timestamp = message.timestamp * 1000,
                ))

                // Send delivery receipt
                val receiptAttrs = mutableMapOf(
                    "id" to message.id,
                    "to" to message.from
                )
                node.attrs["participant"]?.let { receiptAttrs["participant"] = it }
                node.attrs["recipient"]?.let { receiptAttrs["recipient"] = it }
                val receipt = WhatsAppProtocol.Node(tag = "receipt", attrs = receiptAttrs)
                webSocket?.send(WhatsAppProtocol.encodeNode(receipt))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
            }
        }
    }

    /**
     * Handle receipt with per-sender batching for group chats.
     * From Go handleWAReceipt — groups messages by sender.
     */
    private suspend fun handleReceipt(node: WhatsAppProtocol.Node) {
        val receiptType = node.attrs["type"]
        if (receiptType != "read" && receiptType != "read-self") return

        val from = node.attrs["from"] ?: return
        val participant = node.attrs["participant"]

        // Reroute LID sender
        val sender = resolveJID(participant ?: from)

        val messageId = node.attrs["id"] ?: return
        val timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000

        _events.emit(GMEvent.ReadReceipt(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = messageId,
            timestamp = timestamp,
        ))

        // Handle additional message IDs in list node
        val listNode = node.getChildByTag("list")
        listNode?.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { item ->
            val itemId = item.attrs["id"] ?: return@forEach
            _events.emit(GMEvent.ReadReceipt(
                source = MessageSource.WHATSAPP,
                conversationId = "wa:$from",
                messageId = itemId,
                timestamp = timestamp,
            ))
        }
    }

    /**
     * Handle chat presence with media type differentiation.
     * From Go handleWAChatPresence — differentiates text/audio/media typing.
     */
    private suspend fun handleChatPresence(node: WhatsAppProtocol.Node) {
        val from = node.attrs["from"] ?: return
        val participant = node.attrs["participant"]
        val sender = resolveJID(participant ?: from)

        val composingNode = node.getChildByTag("composing")
        val pausedNode = node.getChildByTag("paused")

        val isTyping = composingNode != null
        val mediaAttr = composingNode?.attrs?.get("media")

        _events.emit(GMEvent.TypingIndicator(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            senderId = sender,
            isTyping = isTyping,
        ))
    }

    /**
     * Handle notification events (group changes, mute, pin, archive).
     * From Go handleWAGroupInfoChange, handleWAMute, handleWAArchive, handleWAPin.
     */
    private suspend fun handleNotification(node: WhatsAppProtocol.Node) {
        val notifType = node.attrs["type"] ?: return
        val from = node.attrs["from"] ?: return

        when (notifType) {
            "w:gp2" -> handleGroupNotification(node, from)
            "server_sync" -> handleServerSync(node, from)
        }
    }

    /**
     * Handle group info change notifications.
     * From Go handleWAGroupInfoChange.
     */
    private suspend fun handleGroupNotification(node: WhatsAppProtocol.Node, groupJid: String) {
        node.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { child ->
            when (child.tag) {
                "subject" -> {
                    val newName = child.attrs["subject"] ?: child.data?.let { String(it, Charsets.UTF_8) } ?: return@forEach
                    _events.emit(GMEvent.IncomingMessage(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:$groupJid",
                        messageId = node.attrs["id"] ?: "",
                        body = "[Group name changed to: $newName]",
                        peerName = resolveName(node.attrs["participant"] ?: groupJid),
                        peerPhone = null,
                        timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000,
                    ))
                }
                "description" -> {
                    val newDesc = child.data?.let { String(it, Charsets.UTF_8) } ?: ""
                    _events.emit(GMEvent.IncomingMessage(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:$groupJid",
                        messageId = node.attrs["id"] ?: "",
                        body = "[Group description changed: $newDesc]",
                        peerName = resolveName(node.attrs["participant"] ?: groupJid),
                        peerPhone = null,
                        timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000,
                    ))
                }
                "add", "remove", "promote", "demote" -> {
                    val participants = child.content?.filterIsInstance<WhatsAppProtocol.Node>()
                        ?.mapNotNull { it.attrs["jid"] } ?: emptyList()
                    val action = child.tag
                    _events.emit(GMEvent.IncomingMessage(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:$groupJid",
                        messageId = node.attrs["id"] ?: "",
                        body = "[Group: ${participants.joinToString()} ${action}ed]",
                        peerName = resolveName(node.attrs["participant"] ?: groupJid),
                        peerPhone = null,
                        timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000,
                    ))
                }
            }
        }
    }

    /**
     * Handle server sync notifications (mute, pin, archive).
     * From Go handleWAMute, handleWAArchive, handleWAPin.
     */
    private suspend fun handleServerSync(node: WhatsAppProtocol.Node, from: String) {
        node.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { child ->
            when (child.tag) {
                "collection" -> {
                    val collectionType = child.attrs["name"] ?: return@forEach
                    child.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { patch ->
                        handleAppStatePatch(collectionType, patch)
                    }
                }
            }
        }
    }

    /**
     * Handle app state patch mutations (mute, pin, archive, unread).
     * From Go handleWAMute / handleWAArchive / handleWAPin / handleWAMarkChatAsRead.
     */
    private suspend fun handleAppStatePatch(collectionType: String, patch: WhatsAppProtocol.Node) {
        val chatJid = patch.attrs["jid"] ?: return
        val action = patch.attrs["action"]
        val value = patch.data?.let { String(it, Charsets.UTF_8) }
        val convId = "wa:$chatJid"
        when {
            collectionType == "regular" && action == "mute" -> {
                val muteEnd = value?.toLongOrNull() ?: 0L
                Log.d(TAG, "AppState: mute $chatJid until $muteEnd")
                db?.conversationDao()?.updateMuteEndTime(chatJid, muteEnd)
                _events.emit(GMEvent.ConversationUpdate(
                    source = source, conversationId = convId,
                    peerName = null, peerPhone = null, avatarUrl = null,
                    lastPreview = null, lastTimestamp = 0, unreadCount = 0,
                ))
            }
            collectionType == "regular" && action == "pin" -> {
                val pinned = value == "true" || value == "1"
                Log.d(TAG, "AppState: pin $chatJid = $pinned")
                db?.conversationDao()?.updatePinned(chatJid, pinned)
            }
            collectionType == "regular" && action == "archive" -> {
                val archived = value == "true" || value == "1"
                Log.d(TAG, "AppState: archive $chatJid = $archived")
                db?.conversationDao()?.updateArchived(chatJid, archived)
            }
            collectionType == "regular" && action == "markRead" -> {
                val unread = value == "true" || value == "1"
                Log.d(TAG, "AppState: markedAsUnread $chatJid = $unread")
                db?.conversationDao()?.updateMarkedAsUnread(chatJid, unread)
            }
            collectionType == "regular" && action == "star" -> {
                Log.d(TAG, "AppState: star/favorite $chatJid")
            }
            collectionType == "regular" && action == "delete" -> {
                Log.d(TAG, "AppState: delete chat $chatJid")
                db?.conversationDao()?.delete(chatJid)
                _events.emit(GMEvent.ConversationDeleted(source, convId))
            }
        }
    }

    /**
     * Track undecryptable messages.
     * From Go handleWAUndecryptableMessage / trackUndecryptable.
     */
    private fun trackUndecryptable(node: WhatsAppProtocol.Node) {
        val from = node.attrs["from"] ?: return
        val count = undecryptableTracker.merge(from, 1) { old, new -> old + new } ?: 1
        Log.w(TAG, "Undecryptable message from $from (count: $count)")
    }

    /**
     * Resolve JID: convert LID JIDs to phone number JIDs.
     * Handles DM sender LID, own message LID, broadcast, and bot cases.
     * From Go resolveJID / rerouteWAMessage.
     */
    private fun resolveJID(jid: String): String {
        if (!jid.contains("@lid")) return jid
        // Check direct LID→phone mapping
        lidToPhoneMap[jid]?.let { return it }
        // Check if it matches our own LID
        val ownLid = authData?.lid
        if (ownLid != null && ownLid.isNotEmpty() && jid == ownLid) {
            return authData?.wid ?: jid
        }
        return jid
    }

    /**
     * Store poll option hashes for later vote resolution.
     * From Go wadb.PollOption.
     */
    private suspend fun storePollOptions(messageId: String, options: List<String>) {
        val dao = db?.pollOptionDao() ?: return
        val pollOptions = options.map { option ->
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(option.toByteArray(Charsets.UTF_8))
            WhatsAppPollOption(
                msgId = messageId,
                optionHash = Base64.encodeToString(hash, Base64.NO_WRAP),
                optionName = option,
            )
        }
        dao.upsertAll(pollOptions)
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "Starting history sync")
            try {
                // Request initial history sync from server (Go handleWAAppStateSyncComplete)
                val ws = webSocket ?: return@launch
                val id = WhatsAppProtocol.generateMessageId(authData?.wid)
                val syncNode = WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf(
                        "id" to id,
                        "type" to "set",
                        "xmlns" to "w:web",
                        "to" to "s.whatsapp.net",
                    ),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "web",
                            attrs = mapOf("type" to "initial")
                        )
                    )
                )
                ws.send(WhatsAppProtocol.encodeNode(syncNode))
                Log.i(TAG, "History sync request sent")
            } catch (e: Exception) {
                Log.e(TAG, "History sync failed", e)
            }
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false

        val to = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildTextMessage(to, id, body)
        val data = WhatsAppProtocol.encodeNode(node)

        return ws.send(data)
    }

    private data class MediaUploadResult(
        val url: String,
        val directPath: String,
    )

    private suspend fun uploadMedia(
        encryptedData: ByteArray,
        mediaType: String,
        token: String,
    ): MediaUploadResult = withContext(Dispatchers.IO) {
        val uploadUrl = "https://mmg.whatsapp.net/mms/$mediaType/$token"
        val requestBody = encryptedData.toRequestBody(null)
        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Media upload failed: HTTP ${response.code}")
        }
        val json = JSONObject(response.body?.string() ?: throw Exception("Empty upload response"))
        MediaUploadResult(
            url = json.getString("url"),
            directPath = json.getString("direct_path"),
        )
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val to = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val mediaType = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> "document"
        }
        val mediaKeyStr = when (mediaType) {
            "image" -> WhatsAppProtocol.MEDIA_KEY_IMAGE
            "video" -> WhatsAppProtocol.MEDIA_KEY_VIDEO
            "audio" -> WhatsAppProtocol.MEDIA_KEY_AUDIO
            else -> WhatsAppProtocol.MEDIA_KEY_DOCUMENT
        }

        return try {
            val enc = WhatsAppProtocol.encryptMedia(bytes, mediaKeyStr)
            val token = Base64.encodeToString(enc.fileEncSha256, Base64.URL_SAFE or Base64.NO_WRAP)
            val upload = uploadMedia(enc.encryptedData, mediaType, token)
            val node = WhatsAppProtocol.buildMediaMessage(
                to, id, upload.url, upload.directPath,
                enc.mediaKey, enc.fileSha256, enc.fileEncSha256, enc.fileLength,
                mimeType, fileName, mediaType
            )
            ws.send(WhatsAppProtocol.encodeNode(node))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media", e)
            false
        }
    }

    /**
     * Mark messages as read with per-sender batching for group chats.
     * From Go HandleMatrixReadReceipt — groups messages by sender.
     */
    suspend fun markRead(
        conversationId: String,
        messageIds: List<String> = emptyList(),
        senderJids: Map<String, String> = emptyMap(),
    ) {
        val to = extractJid(conversationId) ?: return
        val ws = webSocket ?: return
        if (messageIds.isEmpty()) return

        // Filter out own messages by checking both JID and LID (Issue 4)
        val ownJid = authData?.wid ?: ""
        val ownLid = authData?.lid ?: ""
        val filteredIds = messageIds.filter { msgId ->
            val sender = senderJids[msgId] ?: ""
            sender != ownJid && (ownLid.isEmpty() || sender != ownLid)
        }
        if (filteredIds.isEmpty()) return

        val isGroup = to.contains("@g.us")
        if (isGroup && senderJids.isNotEmpty()) {
            // Batch by sender for group chats (Go HandleMatrixReadReceipt)
            val bySender = mutableMapOf<String, MutableList<String>>()
            filteredIds.forEach { msgId ->
                val sender = senderJids[msgId] ?: ""
                bySender.getOrPut(sender) { mutableListOf() }.add(msgId)
            }
            bySender.forEach { (sender, ids) ->
                val node = WhatsAppProtocol.buildReadReceipt(
                    chatJid = to,
                    messageIds = ids,
                    senderJid = sender.ifEmpty { null },
                )
                ws.send(WhatsAppProtocol.encodeNode(node))
            }
        } else {
            val node = WhatsAppProtocol.buildReadReceipt(chatJid = to, messageIds = filteredIds)
            ws.send(WhatsAppProtocol.encodeNode(node))
        }
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val strippedEmoji = emoji.replace("\uFE0F", "")
        val node = WhatsAppProtocol.buildReactionMessage(
            chatJid = chatJid,
            senderJid = "",
            targetMessageId = messageId,
            emoji = strippedEmoji,
            ownJid = ownJid,
            id = id,
        )
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Send a typing indicator (chat presence) with media type differentiation.
     * From whatsmeow HandleMatrixTyping / SendChatPresence.
     * Supports: text typing, audio recording, media uploading.
     */
    enum class TypingType { TEXT, RECORDING_AUDIO, UPLOADING_MEDIA }

    suspend fun sendTyping(
        conversationId: String,
        isTyping: Boolean,
        typingType: TypingType = TypingType.TEXT,
    ) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        // Go HandleMatrixTyping: UploadingMedia returns nil (not sent)
        if (typingType == TypingType.UPLOADING_MEDIA) return

        val isAudio = typingType == TypingType.RECORDING_AUDIO
        val node = WhatsAppProtocol.buildChatPresence(chatJid, isTyping, isAudio, authData?.wid ?: "")
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Edit a previously sent message.
     * From whatsmeow HandleMatrixEdit / BuildEdit
     */
    suspend fun sendEdit(conversationId: String, targetMessageId: String, newBody: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildEditMessage(chatJid, targetMessageId, newBody, ownJid, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Revoke (delete) a previously sent message.
     * From whatsmeow HandleMatrixMessageRemove / BuildRevoke
     */
    suspend fun sendRevoke(conversationId: String, targetMessageId: String, senderJid: String = ""): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildRevokeMessage(chatJid, senderJid, targetMessageId, ownJid, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Send a poll creation message.
     * From Go HandleMatrixPollStart / PollStartToWhatsApp.
     */
    suspend fun sendPollCreation(
        conversationId: String,
        question: String,
        options: List<String>,
        selectableCount: Int = 0,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildPollCreationMessage(chatJid, question, options, selectableCount, id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (sent) {
            storePollOptions(id, options)
        }
        return sent
    }

    /**
     * Send a location message.
     * From Go from-matrix.go parseGeoURI / location handling.
     */
    suspend fun sendLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        name: String? = null,
        address: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildLocationMessage(chatJid, latitude, longitude, name, address, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Send a contact/vCard message.
     * From Go wa-contact.go convertContactMessage.
     */
    suspend fun sendContact(
        conversationId: String,
        displayName: String,
        vcard: String,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildContactMessage(chatJid, displayName, vcard, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set disappearing messages timer.
     * From Go HandleMatrixDisappearingTimer.
     * Allowed values: 0 (off), 86400 (24h), 604800 (7d), 7776000 (90d).
     */
    suspend fun setDisappearingTimer(conversationId: String, timerSeconds: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false

        val allowedValues = setOf(0L, 86400L, 604800L, 7776000L)
        if (timerSeconds !in allowedValues) {
            Log.w(TAG, "Invalid disappearing timer value: $timerSeconds")
            return false
        }

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildDisappearingTimerMessage(chatJid, timerSeconds, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set group name.
     * From Go HandleMatrixRoomName / SetGroupName.
     */
    suspend fun setGroupName(conversationId: String, name: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildGroupInfoChange(groupJid, "subject", name, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set group topic/description with old/new ID tracking.
     * From Go HandleMatrixRoomTopic / SetGroupTopic.
     */
    suspend fun setGroupTopic(
        conversationId: String,
        topic: String,
        previousTopicId: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val extraAttrs = mutableMapOf<String, String>()
        if (previousTopicId != null) {
            extraAttrs["prev_v"] = previousTopicId
        }
        extraAttrs["id"] = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildGroupInfoChange(groupJid, "description", topic, id, extraAttrs)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set group avatar with crop/resize/JPEG conversion.
     * Crops to square, scales to max 640x640, encodes as JPEG.
     * From Go HandleMatrixRoomAvatar.
     */
    suspend fun setGroupAvatar(conversationId: String, imageBytes: ByteArray): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val processed = withContext(Dispatchers.Default) {
            cropResizeAvatar(imageBytes)
        } ?: return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:profile:picture",
                "to" to groupJid,
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "picture",
                    attrs = mapOf("type" to "image"),
                    data = processed,
                )
            ),
        )
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    private fun cropResizeAvatar(imageBytes: ByteArray): ByteArray? {
        val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val size = minOf(original.width, original.height)
        val x = (original.width - size) / 2
        val y = (original.height - size) / 2
        val cropped = Bitmap.createBitmap(original, x, y, size, size)
        val maxDim = 640
        val scaled = if (size > maxDim) {
            Bitmap.createScaledBitmap(cropped, maxDim, maxDim, true).also {
                if (it !== cropped) cropped.recycle()
            }
        } else cropped
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== original) scaled.recycle()
        if (original !== cropped && original !== scaled) original.recycle()
        return out.toByteArray()
    }

    /**
     * Add or remove group members.
     * From Go HandleMatrixMembership / UpdateGroupParticipants.
     */
    suspend fun updateGroupParticipants(
        conversationId: String,
        participantJids: List<String>,
        action: String,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val validActions = setOf("add", "remove", "promote", "demote")
        if (action !in validActions) return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildGroupParticipantChange(groupJid, participantJids, action, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Delete a chat, optionally leaving group, with AppState mutations.
     * From Go HandleMatrixDeleteChat.
     */
    suspend fun deleteChat(conversationId: String, leaveGroup: Boolean = true): Boolean {
        val jid = extractJid(conversationId) ?: return false
        val ws = webSocket

        if (leaveGroup && jid.contains("@g.us") && _state.value is State.Connected && ws != null) {
            val id = WhatsAppProtocol.generateMessageId(authData?.wid)
            val ownJid = authData?.wid ?: ""
            if (ownJid.isNotEmpty()) {
                val node = WhatsAppProtocol.buildGroupParticipantChange(
                    jid, listOf(ownJid), "remove", id
                )
                ws.send(WhatsAppProtocol.encodeNode(node))
            }
        }

        // Query last message timestamp for the delete anchor
        val conversation = db?.conversationDao()?.getConversation(jid)
        val lastMsgTimestamp = conversation?.lastMessageTimestamp ?: 0L

        // Push AppState delete mutation (Go HandleMatrixDeleteChat PatchDelete)
        if (_state.value is State.Connected && ws != null) {
            val patchAttrs = mutableMapOf("jid" to jid, "action" to "delete")
            if (lastMsgTimestamp > 0) patchAttrs["messageTimestamp"] = lastMsgTimestamp.toString()

            val patchNode = WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf(
                    "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                    "type" to "set",
                    "xmlns" to "w:sync:app:state",
                    "to" to "s.whatsapp.net",
                ),
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "sync",
                        content = listOf(
                            WhatsAppProtocol.Node(
                                tag = "collection",
                                attrs = mapOf("name" to "regular"),
                                content = listOf(
                                    WhatsAppProtocol.Node(
                                        tag = "patch",
                                        attrs = patchAttrs,
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
            ws.send(WhatsAppProtocol.encodeNode(patchNode))
        }

        db?.conversationDao()?.delete(jid)
        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    suspend fun sendNewThread(recipientJid: String, body: String): String? {
        if (_state.value !is State.Connected) return null
        val ws = webSocket ?: return null
        val jid = if (recipientJid.contains("@")) recipientJid else "$recipientJid@s.whatsapp.net"
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        pendingMessageIDs.add(id)
        val node = WhatsAppProtocol.buildTextMessage(jid, id, body)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        return if (sent) "wa:$jid" else null
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        val jid = extractJid(conversationId) ?: return false
        db?.conversationDao()?.delete(jid)
        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    suspend fun markChatUnread(conversationId: String, unread: Boolean) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = mapOf(
                                        "jid" to chatJid,
                                        "action" to "markRead",
                                    ),
                                    data = if (unread) "true".toByteArray() else "false".toByteArray(),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
    }

    suspend fun setMute(conversationId: String, muteUntilMs: Long) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = mapOf(
                                        "jid" to chatJid,
                                        "action" to "mute",
                                    ),
                                    data = muteUntilMs.toString().toByteArray(),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
        val conv = db?.conversationDao()?.getConversation(chatJid)
        if (conv != null) {
            db?.conversationDao()?.upsert(conv.copy(muteEndTime = muteUntilMs))
        }
    }

    suspend fun togglePin(conversationId: String, pinned: Boolean) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = mapOf(
                                        "jid" to chatJid,
                                        "action" to "pin",
                                    ),
                                    data = if (pinned) "true".toByteArray() else "false".toByteArray(),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
        val conv = db?.conversationDao()?.getConversation(chatJid)
        if (conv != null) {
            db?.conversationDao()?.upsert(conv.copy(pinned = pinned))
        }
    }

    fun isLoggedIn(): Boolean {
        return authData != null && _state.value is State.Connected
    }

    /**
     * Logout from WhatsApp server and clear local data.
     * From whatsmeow LogoutRemote
     */
    fun logoutRemote() {
        scope.launch {
            val ws = webSocket
            val ownJid = authData?.wid ?: ""
            if (ws != null && ownJid.isNotEmpty()) {
                val logoutNode = WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf(
                        "to" to "s.whatsapp.net",
                        "type" to "set",
                        "xmlns" to "md",
                        "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                    ),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "remove-companion-device",
                            attrs = mapOf("jid" to ownJid, "reason" to "user_initiated")
                        )
                    )
                )
                try {
                    ws.send(WhatsAppProtocol.encodeNode(logoutNode))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send logout", e)
                }
            }
            stop()
        }
    }

    private fun extractJid(conversationId: String): String? {
        // Conversation ID format: "wa:{jid}"
        return conversationId.removePrefix("wa:")
    }

    private fun generateMessageId(): String {
        return WhatsAppProtocol.generateMessageId(authData?.wid)
    }

    private fun generateRef(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generateClientId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun resolveName(jid: String): String {
        return nameCache.getOrPut(jid) {
            // Extract phone number from JID for display
            // Format: "1234567890@s.whatsapp.net" or "1234567890-1234567890@g.us"
            val phone = jid.substringBefore("@").substringBefore("-")
            "+$phone"
        }
    }

    fun getContactSuggestions(query: String): List<ContactSuggestion> {
        // Return cached contacts matching query
        return nameCache.entries
            .filter { it.value.contains(query, ignoreCase = true) }
            .map { ContactSuggestion(it.value, null, null, MessageSource.WHATSAPP) }
            .take(10)
    }
}
