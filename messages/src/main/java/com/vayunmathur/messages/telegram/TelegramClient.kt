package com.vayunmathur.messages.telegram

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.ContactSuggestion
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.telegram.api.functions.*
import com.vayunmathur.messages.telegram.api.types.*
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import com.vayunmathur.messages.telegram.mtproto.tl.TlRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

object TelegramClient {

    private const val TAG = "TelegramClient"
    private const val API_ID = 94575
    private const val API_HASH = "a3406de8d171bb422bb6ddf3bbd800e2"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class AwaitingCode(val phone: String) : State
        data class AwaitingPassword(val phone: String, val hint: String) : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.TELEGRAM

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private lateinit var appContext: Context
    private var apiClient: TelegramApiClient? = null
    private var backfillJob: Job? = null
    private var updateJob: Job? = null
    private var pendingPhone: String? = null
    private var phoneCodeHash: String? = null

    private val peerCache = ConcurrentHashMap<Long, TlObject>()
    private val userNameCache = ConcurrentHashMap<Long, String>()
    private val channelMetaCache = ConcurrentHashMap<Long, Channel>()
    private var reconnectAttempt = 0
    private var isPremium = false

    private companion object {
        const val MAX_CAPTION_LENGTH = 2048
        const val MAX_CAPTION_LENGTH_PREMIUM = 4096
        const val MAX_IMAGE_FILE_SIZE = 10 * 1024 * 1024
        const val MAX_IMAGE_ASPECT_RATIO = 20.0
    }

    private var currentPts = 0
    private var currentQts = 0
    private var currentDate = 0
    private var currentSeq = 0

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            val auth = TelegramAuthData.load(appContext)
            if (auth?.loggedIn == true && auth.authKey != null) {
                Log.i(TAG, "resuming from persisted session")
                bootSession(auth)
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = TelegramAuthData.load(appContext)
            if (auth?.loggedIn == true && auth.authKey != null) bootSession(auth)
            else _state.value = State.NeedsSetup
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Telegram session")
        backfillJob?.cancel()
        updateJob?.cancel()
        scope.launch {
            runCatching { apiClient?.invoke(AuthLogOut) { TlRegistry.decode(it) } }
            apiClient?.disconnect()
            apiClient = null
        }
        pendingPhone = null
        phoneCodeHash = null
        peerCache.clear()
        userNameCache.clear()
        channelMetaCache.clear()
        scope.launch { TelegramAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    fun submitPhoneNumber(phone: String) {
        pendingPhone = phone
        _state.value = State.Connecting
        scope.launch {
            try {
                val client = ensureClient()
                val initReq = InitConnection(
                    apiId = API_ID,
                    deviceModel = "Android",
                    systemVersion = "14",
                    appVersion = "1.0",
                    systemLangCode = "en",
                    langPack = "",
                    langCode = "en",
                    inner = AuthSendCode(phone, API_ID, API_HASH),
                )
                val result = client.invoke(initReq) { TlRegistry.decode(it) }
                if (result is AuthSentCode) {
                    phoneCodeHash = result.phoneCodeHash
                    _state.value = State.AwaitingCode(phone)
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitPhoneNumber failed", e)
                _state.value = State.Disconnected("Auth failed: ${e.message}")
            }
        }
    }

    fun submitCode(code: String) {
        val phone = pendingPhone ?: return
        val hash = phoneCodeHash ?: return
        scope.launch {
            try {
                val client = apiClient ?: return@launch
                val result = client.invoke(AuthSignIn(phone, hash, code)) { TlRegistry.decode(it) }
                when (result) {
                    is AuthAuthorization -> onAuthorized(result.user)
                    is AuthPassword -> {
                        val hint = try {
                            val pwd = client.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                            if (pwd is Password) pwd.hint else ""
                        } catch (_: Exception) { "" }
                        _state.value = State.AwaitingPassword(phone, hint)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitCode failed: ${e.message}")
                if (e.message?.contains("SESSION_PASSWORD_NEEDED") == true) {
                    val hint = try {
                        val pwd = apiClient?.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                        if (pwd is Password) pwd.hint else ""
                    } catch (_: Exception) { "" }
                    _state.value = State.AwaitingPassword(phone, hint)
                } else {
                    _state.value = State.AwaitingCode(phone)
                }
            }
        }
    }

    fun submitPassword(password: String) {
        val phone = pendingPhone ?: return
        scope.launch {
            try {
                val client = apiClient ?: return@launch
                val pwd = client.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                if (pwd !is Password) return@launch
                val answer = SRPHelper.computeSRP(password, pwd)
                val inputPassword = InputCheckPasswordSRP(pwd.srpId, answer.a, answer.m1)
                val result = client.invoke(AuthCheckPassword(inputPassword)) { TlRegistry.decode(it) }
                if (result is AuthAuthorization) {
                    onAuthorized(result.user)
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitPassword failed: ${e.message}")
                _state.value = State.AwaitingPassword(phone, "")
            }
        }
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    suspend fun sendMessage(
        conversationId: String,
        body: String,
        noWebpage: Boolean = false,
        entities: List<MessageEntity> = emptyList(),
        replyToMsgId: Int = 0,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        // Parse markdown entities from body if none provided (Issue 13)
        val resolvedEntities = entities.ifEmpty { parseMarkdownEntities(body) }
        val cleanBody = if (entities.isEmpty() && resolvedEntities.isNotEmpty()) stripMarkdown(body) else body
        // Include topic ID in replyTo for forums (Issue 12)
        val topicReplyId = extractTopicId(conversationId) ?: replyToMsgId
        return try {
            client.invoke(MessagesSendMessage(peer, cleanBody, random.nextLong(), noWebpage, resolvedEntities, topicReplyId)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMessage failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun editMessage(
        conversationId: String,
        messageId: Int,
        newText: String,
        noWebpage: Boolean = false,
        entities: List<MessageEntity> = emptyList(),
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesEditMessage(peer, messageId, newText, noWebpage, entities)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "editMessage failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false

        // Truncate caption to max length (Issue 19)
        val maxLen = if (isPremium) MAX_CAPTION_LENGTH_PREMIUM else MAX_CAPTION_LENGTH
        val truncatedCaption = caption?.let {
            if (it.length > maxLen) {
                Log.w(TAG, "Caption truncated from ${it.length} to $maxLen chars")
                it.take(maxLen)
            } else it
        }

        return try {
            val fileId = random.nextLong()
            val partSize = 512 * 1024
            val parts = (bytes.size + partSize - 1) / partSize
            val useBig = bytes.size > 10 * 1024 * 1024

            for (i in 0 until parts) {
                val start = i * partSize
                val end = minOf(start + partSize, bytes.size)
                val chunk = bytes.copyOfRange(start, end)
                if (useBig) {
                    client.invoke(UploadSaveBigFilePart(fileId, i, parts, chunk)) { TlRegistry.decode(it) }
                } else {
                    client.invoke(UploadSaveFilePart(fileId, i, chunk)) { TlRegistry.decode(it) }
                }
            }

            val inputFile: TlObject = if (useBig) InputFileBig(fileId, parts, fileName)
            else InputFile(fileId, parts, fileName)

            // Check image-as-file threshold (Issue 16)
            val media: TlObject = if (mime.startsWith("image/") && !shouldSendImageAsFile(bytes, mime)) {
                InputMediaUploadedPhoto(inputFile)
            } else {
                InputMediaUploadedDocument(inputFile, mime, listOf(DocumentAttributeFilename(fileName)))
            }

            client.invoke(MessagesSendMedia(peer, media, truncatedCaption ?: "", random.nextLong())) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMedia failed: ${humanizeError(t)}")
            false
        }
    }

    private fun shouldSendImageAsFile(bytes: ByteArray, mime: String): Boolean {
        if (bytes.size > MAX_IMAGE_FILE_SIZE) return true
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return false
        val ratio = w.toDouble() / h.toDouble()
        return ratio > MAX_IMAGE_ASPECT_RATIO || ratio < (1.0 / MAX_IMAGE_ASPECT_RATIO)
    }

    suspend fun markRead(conversationId: String, maxId: Int = Int.MAX_VALUE): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsReadHistory(inputChannel, maxId)) { TlRegistry.decode(it) }
                    // Also read mentions and reactions in parallel (Issue 14)
                    scope.launch {
                        try { client.invoke(MessagesReadMentions(peer)) { TlRegistry.decode(it) } }
                        catch (t: Throwable) { Log.d(TAG, "readMentions: ${t.message}") }
                    }
                    scope.launch {
                        try { client.invoke(MessagesReadReactions(peer)) { TlRegistry.decode(it) } }
                        catch (t: Throwable) { Log.d(TAG, "readReactions: ${t.message}") }
                    }
                }
                else -> {
                    client.invoke(MessagesReadHistory(peer, maxId)) { TlRegistry.decode(it) }
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun deleteThread(conversationId: String, revoke: Boolean = false): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        // Check for topic-based deletion (Issue 15)
        val topicId = extractTopicId(conversationId)
        return try {
            when {
                topicId != null && peer is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsDeleteTopicHistory(inputChannel, topicId)) { TlRegistry.decode(it) }
                }
                peer is InputPeerUser -> {
                    client.invoke(MessagesDeleteHistory(peer, revoke = revoke)) { TlRegistry.decode(it) }
                }
                peer is InputPeerChat -> {
                    client.invoke(MessagesDeleteHistory(peer, revoke = revoke)) { TlRegistry.decode(it) }
                    client.invoke(MessagesDeleteChat(peer.chatId)) { TlRegistry.decode(it) }
                }
                peer is InputPeerChannel -> {
                    client.invoke(ChannelsLeaveChannel(
                        InputChannel(peer.channelId, peer.accessHash)
                    )) { TlRegistry.decode(it) }
                }
                else -> {
                    client.invoke(MessagesDeleteHistory(peer, revoke = revoke)) { TlRegistry.decode(it) }
                }
            }
            _events.emit(GMEvent.ConversationDeleted(source, conversationId.substringAfter(':', conversationId)))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteThread failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        add: Boolean,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val msgId = messageId.substringAfterLast('_').toIntOrNull() ?: return false
        return try {
            val reactions = if (add) {
                if (emoji.startsWith("custom:")) {
                    val docId = emoji.removePrefix("custom:").toLongOrNull() ?: 0L
                    listOf(InputMessageReactionCustomEmoji(docId))
                } else {
                    listOf(InputMessageReactionEmoji(fullyQualifyEmoji(emoji)))
                }
            } else emptyList<TlObject>()
            client.invoke(MessagesSendReaction(peer, msgId, reactions)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendReaction failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun sendTyping(conversationId: String, action: TlObject? = null): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesSetTyping(peer, action)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendTyping failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setDisappearingTimer(conversationId: String, seconds: Int): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        // Topics don't support disappearing timer (Issue 20)
        if (isTopicConversation(conversationId)) {
            Log.d(TAG, "Disappearing timer not supported for topics")
            return false
        }
        return try {
            client.invoke(MessagesSetHistoryTTL(peer, seconds)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDisappearingTimer failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setGroupName(conversationId: String, newName: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChat -> {
                    client.invoke(MessagesEditChatTitle(peer.chatId, newName)) { TlRegistry.decode(it) }
                }
                is InputPeerChannel -> {
                    client.invoke(ChannelsEditTitle(InputChannel(peer.channelId, peer.accessHash), newName)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setGroupName failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun togglePin(conversationId: String, pinned: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        // Topics don't support pin (Issue 21)
        if (isTopicConversation(conversationId)) {
            Log.d(TAG, "Pin not supported for topics")
            return false
        }
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesToggleDialogPin(peer, pinned)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "togglePin failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setMute(conversationId: String, muteUntil: Int): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            // Use InputNotifyForumTopic for topics (Issue 21)
            if (isTopicConversation(conversationId)) {
                val topicId = extractTopicId(conversationId)
                if (topicId != null) {
                    client.invoke(AccountUpdateNotifyForumTopic(peer, topicId, muteUntil, muteUntil > 0)) { TlRegistry.decode(it) }
                } else {
                    client.invoke(AccountUpdateNotifySettings(peer, muteUntil, muteUntil > 0)) { TlRegistry.decode(it) }
                }
            } else {
                client.invoke(AccountUpdateNotifySettings(peer, muteUntil, muteUntil > 0)) { TlRegistry.decode(it) }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setMute failed: ${humanizeError(t)}")
            false
        }
    }

    fun fetchMessages(conversationId: String, count: Int = 50) {
        if (_state.value !is State.Connected) return
        scope.launch {
            val client = apiClient ?: return@launch
            val peer = resolvePeer(conversationId) ?: return@launch
            val chatIdStr = conversationId.substringAfter(':', conversationId)
            try {
                val result = client.invoke(MessagesGetHistory(peer, limit = count)) { TlRegistry.decode(it) }
                val messages = extractMessages(result)
                for (msg in messages) {
                    when (msg) {
                        is Message -> _events.emit(msg.toMessageUpdate(chatIdStr))
                        is MessageService -> handleServiceMessageEvent(msg, chatIdStr)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fetchMessages failed: ${t.message}")
            }
        }
    }

    suspend fun sendNewThread(recipients: List<String>, body: String): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val client = apiClient ?: return null
        return try {
            val found = client.invoke(ContactsSearch(recipients.first())) { TlRegistry.decode(it) }
            val users = extractUsers(found)
            val user = users.filterIsInstance<User>().firstOrNull() ?: return null
            val peer = InputPeerUser(user.id, user.accessHash)
            peerCache[user.id] = peer
            client.invoke(MessagesSendMessage(peer, body, random.nextLong())) { TlRegistry.decode(it) }
            kickoffBackfill()
            "${source.idPrefix}:${user.id}"
        } catch (t: Throwable) {
            Log.w(TAG, "sendNewThread failed: ${humanizeError(t)}")
            null
        }
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        val client = apiClient ?: return emptyList()
        return try {
            val result = client.invoke(ContactsSearch(query)) { TlRegistry.decode(it) }
            val users = extractUsers(result)
            users.filterIsInstance<User>().mapNotNull { user ->
                val name = "${user.firstName} ${user.lastName}".trim()
                if (name.isBlank()) return@mapNotNull null
                ContactSuggestion(
                    displayName = name,
                    phoneE164 = user.phone.takeIf { it.isNotBlank() }?.let { "+$it" },
                    avatarUrl = null,
                    source = source,
                    username = user.username.takeIf { it.isNotBlank() },
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchContacts failed: ${t.message}")
            emptyList()
        }
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private suspend fun ensureClient(): TelegramApiClient {
        apiClient?.let { if (it.isConnected) return it }
        val client = TelegramApiClient()
        client.onDisconnected = { handleDisconnect() }
        client.connect()
        apiClient = client
        startUpdateListener(client)
        return client
    }

    private suspend fun bootSession(auth: TelegramAuthData) {
        _state.value = State.Connecting
        try {
            val authKey = Base64.decode(auth.authKey, Base64.NO_WRAP)
            val authKeyId = Base64.decode(auth.authKeyId, Base64.NO_WRAP)
            val computedId = java.security.MessageDigest.getInstance("SHA-1").digest(authKey)
                .copyOfRange(12, 20)
            require(computedId.contentEquals(authKeyId)) { "Corrupted auth key" }
            val client = TelegramApiClient()
            client.connect(
                dc = auth.dc ?: 2,
                existingAuthKey = authKey,
                existingAuthKeyId = authKeyId,
                existingSalt = auth.salt ?: 0L,
                existingSessionId = null,
            )
            apiClient = client
            client.onDisconnected = { handleDisconnect() }
            reconnectAttempt = 0
            _state.value = State.Connected
            startUpdateListener(client)
            kickoffBackfill()
            fetchUpdatesState(client)
        } catch (e: Exception) {
            Log.e(TAG, "bootSession failed: ${e.message}")
            _state.value = State.Disconnected("Boot failed: ${e.message}")
        }
    }

    private fun onAuthorized(user: User) {
        _state.value = State.Connected
        reconnectAttempt = 0
        val client = apiClient ?: return
        scope.launch {
            TelegramAuthData(
                phoneNumber = pendingPhone ?: "",
                loggedIn = true,
                authKey = Base64.encodeToString(client.authKey, Base64.NO_WRAP),
                authKeyId = Base64.encodeToString(client.authKeyId, Base64.NO_WRAP),
                salt = client.salt,
                dc = client.dc,
                serverAddress = null,
            ).save(appContext)
        }
        kickoffBackfill()
        scope.launch { fetchUpdatesState(client) }
    }

    private fun startUpdateListener(client: TelegramApiClient) {
        updateJob?.cancel()
        updateJob = scope.launch {
            client.updates.collect { update ->
                handleUpdate(update)
            }
        }
    }

    private fun handleDisconnect() {
        if (_state.value !is State.Connected) return
        _state.value = State.Disconnected("Connection lost")
        scope.launch {
            val client = apiClient ?: return@launch
            val maxDelay = 60_000L
            val baseDelay = 1_000L
            while (true) {
                val delayMs = minOf(baseDelay * (1L shl minOf(reconnectAttempt, 20)), maxDelay)
                reconnectAttempt++
                Log.i(TAG, "Reconnect attempt $reconnectAttempt, delay ${delayMs}ms")
                delay(delayMs)
                try {
                    client.reconnect()
                    reconnectAttempt = 0
                    _state.value = State.Connected
                    startUpdateListener(client)
                    kickoffBackfill()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt $reconnectAttempt failed: ${e.message}")
                    _state.value = State.Disconnected("Reconnect failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleUpdate(update: TlObject) {
        when (update) {
            is Updates -> {
                cacheUsers(update.users)
                cacheChats(update.chats)
                for (u in update.updates) handleSingleUpdate(u)
                currentDate = update.date
                currentSeq = update.seq
            }
            is UpdatesCombined -> {
                cacheUsers(update.users)
                cacheChats(update.chats)
                for (u in update.updates) handleSingleUpdate(u)
                currentDate = update.date
                currentSeq = update.seq
            }
            is UpdateShort -> handleSingleUpdate(update.update)
            is UpdateShortMessage -> {
                val chatId = update.userId.toString()
                val msgId = "${chatId}_${update.id}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, update.message, update.out, update.date.toLong() * 1000, null))
                if (!update.out) {
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, update.message, userNameCache[update.userId], null, update.date.toLong() * 1000))
                }
                currentPts = update.pts
            }
            is UpdateShortChatMessage -> {
                val chatId = update.chatId.toString()
                val msgId = "${chatId}_${update.id}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, update.message, update.out, update.date.toLong() * 1000, userNameCache[update.fromId]))
                if (!update.out) {
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, update.message, userNameCache[update.fromId], null, update.date.toLong() * 1000))
                }
                currentPts = update.pts
            }
            is UpdatesTooLong -> {
                scope.launch { recoverGap() }
            }
        }
    }

    private suspend fun handleSingleUpdate(update: TlObject) {
        when (update) {
            is UpdateNewMessage -> {
                when (val msg = update.message) {
                    is Message -> {
                        val chatId = peerToId(msg.peerId)
                        _events.emit(msg.toMessageUpdate(chatId))
                        if (!msg.out) {
                            _events.emit(GMEvent.IncomingMessage(source, chatId, "${chatId}_${msg.id}", renderBody(msg), senderName(msg.fromId), null, msg.date.toLong() * 1000))
                        }
                    }
                    is MessageService -> {
                        val chatId = peerToId(msg.peerId)
                        handleServiceMessageEvent(msg, chatId)
                    }
                }
                currentPts = update.pts
            }
            is UpdateNewChannelMessage -> {
                when (val msg = update.message) {
                    is Message -> {
                        val chatId = peerToId(msg.peerId)
                        _events.emit(msg.toMessageUpdate(chatId))
                        if (!msg.out) {
                            _events.emit(GMEvent.IncomingMessage(source, chatId, "${chatId}_${msg.id}", renderBody(msg), senderName(msg.fromId), null, msg.date.toLong() * 1000))
                        }
                    }
                    is MessageService -> {
                        val chatId = peerToId(msg.peerId)
                        handleServiceMessageEvent(msg, chatId)
                    }
                }
                currentPts = update.pts
            }
            is UpdateDeleteMessages -> {
                for (id in update.messages) {
                    _events.emit(GMEvent.MessageDeleted(source, id.toString()))
                }
                currentPts = update.pts
            }
            is UpdateDeleteChannelMessages -> {
                for (id in update.messages) {
                    _events.emit(GMEvent.MessageDeleted(source, id.toString()))
                }
                currentPts = update.pts
            }
            is UpdateEditMessage -> {
                val msg = update.message as? Message ?: return
                val chatId = peerToId(msg.peerId)
                if (msg.editDate > 0) {
                    _events.emit(GMEvent.MessageEdited(source, chatId, "${chatId}_${msg.id}", renderBody(msg), msg.editDate.toLong() * 1000))
                }
                _events.emit(msg.toMessageUpdate(chatId))
                currentPts = update.pts
            }
            is UpdateEditChannelMessage -> {
                val msg = update.message as? Message ?: return
                val chatId = peerToId(msg.peerId)
                if (msg.editDate > 0) {
                    _events.emit(GMEvent.MessageEdited(source, chatId, "${chatId}_${msg.id}", renderBody(msg), msg.editDate.toLong() * 1000))
                }
                _events.emit(msg.toMessageUpdate(chatId))
                currentPts = update.pts
            }
            is UpdateReadHistoryInbox -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateReadHistoryOutbox -> {
                val chatId = peerToId(update.peer)
                if (update.peer is PeerUser) {
                    _events.emit(GMEvent.ReadReceipt(source, chatId, "${chatId}_${update.maxId}", null, System.currentTimeMillis()))
                }
            }
            is UpdateReadChannelInbox -> {
                val chatId = update.channelId.toString()
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateReadChannelOutbox -> {
                val chatId = update.channelId.toString()
                _events.emit(GMEvent.ReadReceipt(source, chatId, "${chatId}_${update.maxId}", null, System.currentTimeMillis()))
            }
            is UpdateChannel -> {
                val chatId = update.channelId.toString()
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateUserTyping -> {
                val chatId = update.userId.toString()
                val isTyping = update.actionTypeId != 0xfd5ec8f5.toInt() // not cancel
                _events.emit(GMEvent.TypingIndicator(source, chatId, update.userId.toString(), isTyping))
            }
            is UpdateChatUserTyping -> {
                val chatId = update.chatId.toString()
                val senderId = peerToId(update.fromId)
                val isTyping = update.actionTypeId != 0xfd5ec8f5.toInt()
                _events.emit(GMEvent.TypingIndicator(source, chatId, senderId, isTyping))
            }
            is UpdateChannelUserTyping -> {
                val chatId = update.channelId.toString()
                val senderId = peerToId(update.fromId)
                val isTyping = update.actionTypeId != 0xfd5ec8f5.toInt()
                _events.emit(GMEvent.TypingIndicator(source, chatId, senderId, isTyping))
            }
            is UpdateMessageReactions -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateUserName -> {
                val name = "${update.firstName} ${update.lastName}".trim()
                if (name.isNotBlank()) userNameCache[update.userId] = name
            }
            is UpdateNotifySettings -> {
                // Mute settings changed
            }
            is UpdatePinnedDialogs -> {
                // Pin state changed
            }
        }
    }

    private suspend fun handleServiceMessageEvent(msg: MessageService, chatId: String) {
        val action = msg.action ?: return
        val senderName = senderName(msg.fromId)
        when (action) {
            is MessageActionChatEditTitle -> {
                _events.emit(GMEvent.ConversationNameChanged(source, chatId, action.title))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} changed the group name to \"${action.title}\"",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatEditPhoto -> {
                _events.emit(GMEvent.ConversationAvatarChanged(source, chatId, null))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} changed the group photo",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatDeletePhoto -> {
                _events.emit(GMEvent.ConversationAvatarChanged(source, chatId, null))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} removed the group photo",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatAddUser -> {
                for (userId in action.users) {
                    _events.emit(GMEvent.ParticipantAdded(source, chatId, userId.toString()))
                }
                val addedNames = action.users.joinToString(", ") { userNameCache[it] ?: it.toString() }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} added $addedNames",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatDeleteUser -> {
                _events.emit(GMEvent.ParticipantRemoved(source, chatId, action.userId.toString()))
                val removedName = userNameCache[action.userId] ?: action.userId.toString()
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} removed $removedName",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatJoinedByLink -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} joined via invite link",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionSetMessagesTTL -> {
                val body = if (action.period > 0) {
                    "${senderName ?: "Someone"} set messages to auto-delete in ${formatTTL(action.period)}"
                } else {
                    "${senderName ?: "Someone"} disabled auto-delete"
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionPhoneCall -> {
                val body = buildString {
                    if (action.video) append("Video call") else append("Voice call")
                    if (action.duration > 0) append(" (${action.duration}s)")
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatCreate -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} created the group \"${action.title}\"",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatMigrateTo -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Group upgraded to supergroup",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionPinMessage -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} pinned a message",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionTopicCreate -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Topic \"${action.title}\" created",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionGroupCall -> {
                val body = if (action.duration > 0) "Group call (${action.duration}s)" else "Group call started"
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChannelCreate -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Channel created", msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionUnknown -> {}
        }
    }

    private fun formatTTL(seconds: Int): String = when {
        seconds >= 86400 * 7 -> "${seconds / (86400 * 7)} week(s)"
        seconds >= 86400 -> "${seconds / 86400} day(s)"
        seconds >= 3600 -> "${seconds / 3600} hour(s)"
        seconds >= 60 -> "${seconds / 60} minute(s)"
        else -> "$seconds second(s)"
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            val client = apiClient ?: return@launch
            Log.i(TAG, "kickoffBackfill")
            try {
                val result = client.invoke(
                    MessagesGetDialogs(offsetPeer = InputPeerSelf, limit = 200)
                ) { TlRegistry.decode(it) }

                val users = extractUsers(result)
                val chats = extractChats(result)
                cacheUsers(users)
                cacheChats(chats)

                val dialogs = extractDialogs(result)
                val messages = extractMessages(result)

                val messageMap = mutableMapOf<Int, Message>()
                for (m in messages) {
                    if (m is Message) messageMap[m.id] = m
                }

                for (dialog in dialogs) {
                    val chatId = peerToId(dialog.peer)
                    val lastMsg = messageMap[dialog.topMessage]
                    val preview = lastMsg?.let { extractPreview(it) }
                    val tsMs = (lastMsg?.date?.toLong() ?: 0L) * 1000L
                    val isGroup = dialog.peer is PeerChat || dialog.peer is PeerChannel
                    val name = resolvePeerName(dialog.peer)
                    val convType = when (dialog.peer) {
                        is PeerChannel -> {
                            val ch = channelMetaCache[dialog.peer.channelId]
                            when {
                                ch?.forum == true -> "Forum"
                                ch?.megagroup == true -> "Supergroup"
                                else -> "Channel"
                            }
                        }
                        is PeerChat -> "Group"
                        else -> "Telegram"
                    }

                    _events.emit(
                        GMEvent.ConversationUpdate(
                            source = source,
                            conversationId = chatId,
                            peerName = name,
                            peerPhone = null,
                            avatarUrl = null,
                            lastPreview = preview,
                            lastTimestamp = tsMs,
                            unreadCount = dialog.unreadCount,
                            isGroup = isGroup,
                            conversationType = convType,
                        )
                    )

                    cachePeerFromDialog(dialog.peer)
                }

                for (dialog in dialogs) {
                    val chatId = peerToId(dialog.peer)
                    val peer = resolvePeer("${source.idPrefix}:$chatId") ?: continue
                    try {
                        val histResult = client.invoke(MessagesGetHistory(peer, limit = 50)) { TlRegistry.decode(it) }
                        val histMsgs = extractMessages(histResult)
                        cacheUsers(extractUsers(histResult))
                        for (msg in histMsgs) {
                            when (msg) {
                                is Message -> _events.emit(msg.toMessageUpdate(chatId))
                                is MessageService -> handleServiceMessageEvent(msg, chatId)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "backfill history for $chatId failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "backfill failed: ${t.message}")
            }
        }
    }

    private suspend fun fetchUpdatesState(client: TelegramApiClient) {
        try {
            val state = client.invoke(UpdatesGetState) { TlRegistry.decode(it) }
            if (state is UpdatesState) {
                currentPts = state.pts
                currentQts = state.qts
                currentDate = state.date
                currentSeq = state.seq
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getState failed: ${t.message}")
        }
    }

    private suspend fun recoverGap() {
        if (currentPts == 0) return
        val client = apiClient ?: return
        try {
            val diff = client.invoke(UpdatesGetDifference(currentPts, currentDate, currentQts)) { TlRegistry.decode(it) }
            if (diff is UpdatesDifference) {
                cacheUsers(diff.users)
                cacheChats(diff.chats)
                for (msg in diff.newMessages) {
                    when (msg) {
                        is Message -> {
                            val chatId = peerToId(msg.peerId)
                            _events.emit(msg.toMessageUpdate(chatId))
                        }
                        is MessageService -> {
                            val chatId = peerToId(msg.peerId)
                            handleServiceMessageEvent(msg, chatId)
                        }
                    }
                }
                for (u in diff.otherUpdates) handleSingleUpdate(u)
                currentPts = diff.state.pts
                currentQts = diff.state.qts
                currentDate = diff.state.date
                currentSeq = diff.state.seq
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recoverGap failed: ${t.message}")
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun resolvePeer(conversationId: String): TlObject? {
        val idStr = conversationId.substringAfter(':', conversationId)
        val id = idStr.toLongOrNull() ?: return null
        return peerCache[id]
    }

    private fun peerToId(peer: TlObject): String = when (peer) {
        is PeerUser -> peer.userId.toString()
        is PeerChat -> peer.chatId.toString()
        is PeerChannel -> peer.channelId.toString()
        else -> "0"
    }

    private fun senderName(fromId: TlObject?): String? {
        if (fromId is PeerUser) return userNameCache[fromId.userId]
        return null
    }

    private fun resolvePeerName(peer: TlObject): String? = when (peer) {
        is PeerUser -> userNameCache[peer.userId]
        is PeerChat -> userNameCache[peer.chatId]
        is PeerChannel -> userNameCache[peer.channelId]
        else -> null
    }

    private fun cachePeerFromDialog(peer: TlObject) {
        when (peer) {
            is PeerUser -> {
                // Already cached from users list during backfill
            }
            is PeerChat -> {
                peerCache[peer.chatId] = InputPeerChat(peer.chatId)
            }
            is PeerChannel -> {
                val cached = peerCache[peer.channelId]
                if (cached is InputPeerChannel) {
                    peerCache[peer.channelId] = cached
                }
            }
        }
    }

    private fun cacheUsers(users: List<TlObject>) {
        for (u in users) {
            if (u is User) {
                val name = "${u.firstName} ${u.lastName}".trim()
                if (name.isNotBlank()) userNameCache[u.id] = name
                peerCache[u.id] = InputPeerUser(u.id, u.accessHash)
            }
        }
    }

    private fun cacheChats(chats: List<TlObject>) {
        for (c in chats) {
            when (c) {
                is Chat -> {
                    userNameCache[c.id] = c.title
                    peerCache[c.id] = InputPeerChat(c.id)
                }
                is Channel -> {
                    userNameCache[c.id] = c.title
                    peerCache[c.id] = InputPeerChannel(c.id, c.accessHash)
                    channelMetaCache[c.id] = c
                }
            }
        }
    }

    private fun extractMessages(result: TlObject): List<TlObject> = when (result) {
        is MessagesMessages -> result.messages
        is MessagesMessagesSlice -> result.messages
        is MessagesChannelMessages -> result.messages
        else -> emptyList()
    }

    private fun extractUsers(result: TlObject): List<TlObject> = when (result) {
        is MessagesMessages -> result.users
        is MessagesMessagesSlice -> result.users
        is MessagesChannelMessages -> result.users
        is MessagesDialogs -> result.users
        is MessagesDialogsSlice -> result.users
        is ContactsFound -> result.users
        else -> emptyList()
    }

    private fun extractChats(result: TlObject): List<TlObject> = when (result) {
        is MessagesDialogs -> result.chats
        is MessagesDialogsSlice -> result.chats
        is MessagesMessages -> result.chats
        is MessagesMessagesSlice -> result.chats
        else -> emptyList()
    }

    private fun extractDialogs(result: TlObject): List<Dialog> = when (result) {
        is MessagesDialogs -> result.dialogs
        is MessagesDialogsSlice -> result.dialogs
        else -> emptyList()
    }

    private fun renderBody(msg: Message): String {
        val text = msg.message
        if (text.isBlank()) return extractPreview(msg) ?: ""
        if (msg.entities.isEmpty()) return buildBodyWithContext(msg, text)

        val sb = StringBuilder()
        var lastEnd = 0
        val sorted = msg.entities.sortedBy { it.offset }
        for (entity in sorted) {
            val start = entity.offset.coerceAtMost(text.length)
            val end = (entity.offset + entity.length).coerceAtMost(text.length)
            if (start < lastEnd) continue
            if (start > lastEnd) sb.append(text, lastEnd, start)
            val slice = text.substring(start, end)
            when (entity.type) {
                "bold" -> sb.append("*").append(slice).append("*")
                "italic" -> sb.append("_").append(slice).append("_")
                "code" -> sb.append("`").append(slice).append("`")
                "pre" -> sb.append("```").append(slice).append("```")
                "strikethrough" -> sb.append("~").append(slice).append("~")
                "underline" -> sb.append(slice)
                "textUrl" -> sb.append(slice).append(" (").append(entity.url).append(")")
                "spoiler" -> sb.append("||").append(slice).append("||")
                "blockquote" -> sb.append("> ").append(slice)
                else -> sb.append(slice)
            }
            lastEnd = end
        }
        if (lastEnd < text.length) sb.append(text, lastEnd, text.length)

        return buildBodyWithContext(msg, sb.toString())
    }

    private fun buildBodyWithContext(msg: Message, body: String): String {
        val parts = mutableListOf<String>()
        msg.fwdFrom?.let { fwd ->
            val fwdName = fwd.fromName.ifBlank {
                fwd.fromId?.let {
                    if (it is PeerUser) userNameCache[it.userId] else null
                } ?: "Unknown"
            }
            parts.add("Forwarded from $fwdName:")
        }
        if (msg.replyToMsgId != 0) {
            parts.add("[Reply to ${msg.replyToMsgId}]")
        }
        parts.add(body)
        val media = msg.media
        if (media != null && media !is MessageMediaEmpty) {
            val mediaText = when (media) {
                is MessageMediaWebPage -> if (media.url.isNotBlank()) "[Link: ${media.url}]" else null
                is MessageMediaPhoto -> "[Photo]"
                is MessageMediaDocument -> when {
                    (media as MessageMediaDocument).isSticker -> {
                        val alt = media.stickerAlt.ifBlank { null }
                        val stickerType = when {
                            media.mimeType == "application/x-tgsticker" -> "animated"
                            media.mimeType == "video/webm" -> "video"
                            else -> "static"
                        }
                        alt ?: "[Sticker ($stickerType)]"
                    }
                    media.isVoice -> "[Voice message]"
                    media.isRoundVideo -> "[Video message]"
                    media.isVideo -> "[Video]"
                    media.isAnimated -> "[GIF]"
                    media.mimeType.startsWith("audio/") -> "[Audio]"
                    media.fileName.isNotBlank() -> "[File: ${media.fileName}]"
                    else -> "[File]"
                }
                is MessageMediaContact -> {
                    val name = "${media.firstName} ${media.lastName}".trim()
                    val phone = media.phoneNumber
                    if (phone.isNotBlank()) "[Contact: $name ($phone)]" else "[Contact: $name]"
                }
                is MessageMediaGeo -> "[Location: ${media.geoUri()}]"
                is MessageMediaGeoLive -> "[Live Location: ${media.geoUri()}]"
                is MessageMediaVenue -> if (media.title.isNotBlank()) "[Venue: ${media.title} at ${media.geoUri()}]" else "[Venue]"
                is MessageMediaPoll -> renderPoll(media)
                is MessageMediaDice -> renderDice(media)
                else -> null
            }
            if (mediaText != null && body.isBlank()) {
                parts.clear()
                msg.fwdFrom?.let { fwd ->
                    val fwdName = fwd.fromName.ifBlank { "Unknown" }
                    parts.add("Forwarded from $fwdName:")
                }
                parts.add(mediaText)
            } else if (mediaText != null) {
                parts.add(mediaText)
            }
        }
        return parts.joinToString("\n")
    }

    private fun renderPoll(poll: MessageMediaPoll): String {
        return if (poll.pollQuestion.isNotBlank()) "[Poll: ${poll.pollQuestion}]" else "[Poll]"
    }

    private fun renderDice(dice: MessageMediaDice): String {
        return when (dice.emoticon) {
            "🎯" -> "Dart: ${dice.value}"
            "🎲" -> "Dice: ${dice.value}"
            "🏀" -> "Basketball: ${if (dice.value >= 4) "Score!" else "Miss"}"
            "⚽" -> "Football: ${if (dice.value >= 3) "Goal!" else "Miss"}"
            "🎳" -> "Bowling: ${dice.value} pins"
            "🎰" -> "Slots: ${dice.value}"
            else -> "${dice.emoticon} ${dice.value}"
        }
    }

    private fun extractPreview(msg: Message): String? {
        val text = msg.message
        if (text.isNotBlank()) {
            if (msg.entities.isNotEmpty()) return renderBody(msg)
            return buildBodyWithContext(msg, text)
        }
        val media = msg.media
        return when (media) {
            is MessageMediaPhoto -> "[Photo]"
            is MessageMediaDocument -> when {
                media.isSticker -> {
                    val alt = media.stickerAlt.ifBlank { null }
                    val stickerType = when {
                        media.mimeType == "application/x-tgsticker" -> "animated"
                        media.mimeType == "video/webm" -> "video"
                        else -> "static"
                    }
                    alt ?: "[Sticker ($stickerType)]"
                }
                media.isVoice -> "[Voice message]"
                media.isRoundVideo -> "[Video message]"
                media.isVideo -> "[Video]"
                media.isAnimated -> "[GIF]"
                media.mimeType.startsWith("audio/") -> "[Audio]"
                media.mimeType.startsWith("image/") -> "[Image]"
                media.fileName.isNotBlank() -> "[File: ${media.fileName}]"
                else -> "[File]"
            }
            is MessageMediaContact -> {
                val name = "${media.firstName} ${media.lastName}".trim()
                "[Contact: $name${if (media.phoneNumber.isNotBlank()) " (${media.phoneNumber})" else ""}]"
            }
            is MessageMediaGeo -> "[Location]"
            is MessageMediaGeoLive -> "[Live Location]"
            is MessageMediaVenue -> if (media.title.isNotBlank()) "[Venue: ${media.title}]" else "[Venue]"
            is MessageMediaPoll -> renderPoll(media)
            is MessageMediaDice -> renderDice(media)
            is MessageMediaWebPage -> if (media.url.isNotBlank()) "[Link: ${media.url}]" else "[Link Preview]"
            is MessageMediaUnsupported -> "[Unsupported message]"
            else -> if (msg.mediaTypeId != 0) "[Media]" else null
        }
    }

    private fun Message.toMessageUpdate(chatId: String): GMEvent.MessageUpdate {
        val msgId = "${chatId}_${this.id}"
        val body = renderBody(this)
        val tsMs = this.date.toLong() * 1000L
        return GMEvent.MessageUpdate(
            source = source,
            conversationId = chatId,
            messageId = msgId,
            body = body,
            outgoing = this.out,
            timestamp = tsMs,
            senderName = senderName(this.fromId),
        )
    }

    // ----------------------------------------------------------------
    // Membership handling (Issue 11)
    // ----------------------------------------------------------------

    suspend fun inviteMember(conversationId: String, userIds: List<Long>): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputUsers = userIds.mapNotNull { uid ->
                        val cached = peerCache[uid]
                        if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else null
                    }
                    client.invoke(ChannelsInviteToChannel(
                        InputChannel(peer.channelId, peer.accessHash), inputUsers
                    )) { TlRegistry.decode(it) }
                }
                is InputPeerChat -> {
                    for (uid in userIds) {
                        val cached = peerCache[uid]
                        if (cached is InputPeerUser) {
                            client.invoke(MessagesAddChatUser(
                                peer.chatId, InputUser(cached.userId, cached.accessHash)
                            )) { TlRegistry.decode(it) }
                        }
                    }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "inviteMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun kickMember(conversationId: String, userId: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val cached = peerCache[userId]
        val inputUser = if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    client.invoke(ChannelsEditBanned(
                        InputChannel(peer.channelId, peer.accessHash), inputUser
                    )) { TlRegistry.decode(it) }
                }
                is InputPeerChat -> {
                    client.invoke(MessagesDeleteChatUser(peer.chatId, inputUser)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "kickMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun banMember(conversationId: String, userId: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        if (peer !is InputPeerChannel) return false
        val cached = peerCache[userId]
        val inputUser = if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else return false
        return try {
            client.invoke(ChannelsEditBanned(
                InputChannel(peer.channelId, peer.accessHash), inputUser, bannedRights = 1L
            )) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "banMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun leaveGroup(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    client.invoke(ChannelsLeaveChannel(
                        InputChannel(peer.channelId, peer.accessHash)
                    )) { TlRegistry.decode(it) }
                }
                is InputPeerChat -> {
                    client.invoke(MessagesDeleteChatUser(
                        peer.chatId, InputUser(0, 0) // self
                    )) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "leaveGroup failed: ${humanizeError(t)}")
            false
        }
    }

    // ----------------------------------------------------------------
    // Forum/topic helpers (Issue 12)
    // ----------------------------------------------------------------

    private fun isTopicConversation(conversationId: String): Boolean {
        return conversationId.contains("_topic_")
    }

    private fun extractTopicId(conversationId: String): Int? {
        val parts = conversationId.split("_topic_")
        return if (parts.size == 2) parts[1].toIntOrNull() else null
    }

    // ----------------------------------------------------------------
    // Markdown parsing for outbound entities (Issue 13)
    // ----------------------------------------------------------------

    private fun parseMarkdownEntities(text: String): List<MessageEntity> {
        val entities = mutableListOf<MessageEntity>()
        val patterns = listOf(
            "\\*\\*(.+?)\\*\\*" to "bold",
            "\\*(.+?)\\*" to "italic",
            "__(.+?)__" to "underline",
            "~~(.+?)~~" to "strikethrough",
            "```(.+?)```" to "pre",
            "`(.+?)`" to "code",
            "\\|\\|(.+?)\\|\\|" to "spoiler",
        )
        for ((pattern, type) in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            for (match in regex.findAll(text)) {
                entities.add(MessageEntity(
                    type = type,
                    offset = match.range.first,
                    length = match.groupValues[1].length,
                ))
            }
        }
        // URL detection
        val urlRegex = Regex("\\[(.+?)]\\((.+?)\\)")
        for (match in urlRegex.findAll(text)) {
            entities.add(MessageEntity(
                type = "textUrl",
                offset = match.range.first,
                length = match.groupValues[1].length,
                url = match.groupValues[2],
            ))
        }
        return entities.sortedBy { it.offset }
    }

    private fun stripMarkdown(text: String): String {
        var result = text
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        result = result.replace(Regex("\\*(.+?)\\*"), "$1")
        result = result.replace(Regex("__(.+?)__"), "$1")
        result = result.replace(Regex("~~(.+?)~~"), "$1")
        result = result.replace(Regex("```(.+?)```", RegexOption.DOT_MATCHES_ALL), "$1")
        result = result.replace(Regex("`(.+?)`"), "$1")
        result = result.replace(Regex("\\|\\|(.+?)\\|\\|"), "$1")
        result = result.replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
        return result
    }

    // ----------------------------------------------------------------
    // Error humanization (Issue 17)
    // ----------------------------------------------------------------

    private fun humanizeError(t: Throwable): String {
        val msg = t.message ?: return t.toString()
        return when {
            msg.contains("PEER_ID_INVALID") -> "Invalid peer: the user or chat could not be found"
            msg.contains("CHAT_WRITE_FORBIDDEN") -> "You can't write in this chat"
            msg.contains("CHAT_ADMIN_REQUIRED") -> "Admin privileges are required"
            msg.contains("CHAT_FORBIDDEN") -> "You cannot write in this chat"
            msg.contains("USER_BANNED_IN_CHANNEL") -> "You're banned from sending messages in this channel"
            msg.contains("USER_NOT_PARTICIPANT") -> "You are not a member of this chat"
            msg.contains("CHANNEL_PRIVATE") -> "This channel is private"
            msg.contains("MESSAGE_TOO_LONG") -> "Message was too long"
            msg.contains("MESSAGE_EMPTY") -> "Message is empty"
            msg.contains("MESSAGE_NOT_MODIFIED") -> "Message content was not changed"
            msg.contains("MESSAGE_EDIT_TIME_EXPIRED") -> "You can't edit this message anymore"
            msg.contains("MEDIA_CAPTION_TOO_LONG") -> "The caption is too long"
            msg.contains("MEDIA_EMPTY") -> "The provided media is invalid"
            msg.contains("FLOOD_WAIT") -> "Too many requests, please wait"
            msg.contains("SLOWMODE_WAIT") -> "Slow mode is active, please wait"
            msg.contains("USER_PRIVACY_RESTRICTED") -> "The user's privacy settings don't allow this"
            msg.contains("REACTIONS_TOO_MANY") -> "Too many different reactions on this message"
            msg.contains("REACTION_INVALID") -> "Invalid reaction"
            msg.contains("PHONE_NUMBER_INVALID") -> "The phone number is invalid"
            msg.contains("PHONE_CODE_INVALID") -> "The verification code is invalid"
            msg.contains("SESSION_PASSWORD_NEEDED") -> "Two-factor authentication is required"
            msg.contains("CHANNEL_FORUM_MISSING") -> "This channel is not a forum"
            msg.contains("TOPIC_DELETED") -> "The topic was deleted"
            else -> msg
        }
    }

    private fun handleSponsoredMessage() {
        Log.i(TAG, "Sponsored messages are not supported on this client")
    }

    suspend fun setGroupAvatar(conversationId: String, imageBytes: ByteArray): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            val fileId = random.nextLong()
            val partSize = 512 * 1024
            val parts = (imageBytes.size + partSize - 1) / partSize
            for (i in 0 until parts) {
                val start = i * partSize
                val end = minOf(start + partSize, imageBytes.size)
                val chunk = imageBytes.copyOfRange(start, end)
                client.invoke(UploadSaveFilePart(fileId, i, chunk)) { TlRegistry.decode(it) }
            }
            val inputFile = InputFile(fileId, parts, "avatar.jpg")
            val inputPhoto = InputChatUploadedPhoto(inputFile)
            when (peer) {
                is InputPeerChat -> {
                    client.invoke(MessagesEditChatPhoto(peer.chatId, inputPhoto)) { TlRegistry.decode(it) }
                }
                is InputPeerChannel -> {
                    client.invoke(ChannelsEditPhoto(InputChannel(peer.channelId, peer.accessHash), inputPhoto)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setGroupAvatar failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun removeGroupAvatar(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            val emptyPhoto = InputChatPhotoEmpty
            when (peer) {
                is InputPeerChat -> {
                    client.invoke(MessagesEditChatPhoto(peer.chatId, emptyPhoto)) { TlRegistry.decode(it) }
                }
                is InputPeerChannel -> {
                    client.invoke(ChannelsEditPhoto(InputChannel(peer.channelId, peer.accessHash), emptyPhoto)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "removeGroupAvatar failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun deleteMessage(
        conversationId: String,
        messageId: Int,
        revokeForEveryone: Boolean = true,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsDeleteMessages(inputChannel, listOf(messageId))) { TlRegistry.decode(it) }
                }
                else -> {
                    client.invoke(MessagesDeleteMessages(listOf(messageId), revokeForEveryone)) { TlRegistry.decode(it) }
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteMessage failed: ${humanizeError(t)}")
            false
        }
    }

    private fun fullyQualifyEmoji(emoji: String): String {
        if (emoji.codePointCount(0, emoji.length) == 1) {
            val cp = emoji.codePointAt(0)
            if (cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
                cp in 0x2B50..0x2B55 || cp in 0x200D..0x200D ||
                cp in 0x2702..0x27B0 || cp in 0x3030..0x3030 ||
                cp in 0x303D..0x303D || cp in 0x3297..0x3299 ||
                cp in 0x2049..0x2139 || cp in 0x231A..0x231B ||
                cp in 0x25AA..0x25FE || cp in 0x2934..0x2935 ||
                cp in 0x2194..0x21AA || cp in 0x00A9..0x00AE) {
                return emoji + "\uFE0F"
            }
        }
        return emoji
    }
}
