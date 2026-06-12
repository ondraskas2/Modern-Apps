package com.vayunmathur.messages.signal

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.signal.auth.PreKeyManager
import com.vayunmathur.messages.signal.auth.Provisioning
import com.vayunmathur.messages.signal.contacts.ContactDiscovery
import com.vayunmathur.messages.signal.contacts.ContactManager
import com.vayunmathur.messages.signal.contacts.ProfileManager
import com.vayunmathur.messages.signal.contacts.StorageServiceManager
import com.vayunmathur.messages.signal.groups.GroupManager
import com.vayunmathur.messages.signal.groups.SenderKeyManager
import com.vayunmathur.messages.signal.media.AttachmentManager
import com.vayunmathur.messages.signal.receiving.ContentDispatcher
import com.vayunmathur.messages.signal.receiving.DecryptedMessage
import com.vayunmathur.messages.signal.receiving.EnvelopeDecryptor
import com.vayunmathur.messages.signal.receiving.MessageContent
import com.vayunmathur.messages.signal.sending.ContentBuilders
import com.vayunmathur.messages.signal.sending.DeviceManager
import com.vayunmathur.messages.signal.sending.MessageSender
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalIdentityKeyStore
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.store.SignalSenderKeyStore
import com.vayunmathur.messages.signal.store.SignalSessionStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.proto.WebSocketProtos
import com.vayunmathur.messages.data.buildMessagesDatabase
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SignalClient {

    private const val TAG = "SignalClient"
    private const val MESSAGE_DELETE_MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingQrScan(val qrUrl: String) : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.SIGNAL

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private var authData: SignalAuthData? = null
    private var webSocket: SignalWebSocket? = null
    private var unauthedWebSocket: SignalWebSocket? = null
    private var backfillJob: Job? = null
    private var provisioningJob: Job? = null
    private var keyCheckJob: Job? = null

    private var db: SignalDatabase? = null
    private var sessionStore: SignalSessionStore? = null
    private var identityKeyStore: SignalIdentityKeyStore? = null
    private var preKeyStore: SignalPreKeyStore? = null
    private var senderKeyStore: SignalSenderKeyStore? = null
    private var messageSender: MessageSender? = null
    private var contactManager: ContactManager? = null
    private var profileManager: ProfileManager? = null
    private var groupManager: GroupManager? = null
    private var contactDiscovery: ContactDiscovery? = null
    var syncContactsOnConnect: Boolean = false
    private var lastContactRequestTime: Long = 0L
    val encryptionLock = Mutex()

    private val nameCache = ConcurrentHashMap<String, String>()

    fun isConnected(): Boolean {
        return webSocket?.isConnected() == true && unauthedWebSocket?.isConnected() == true
    }

    fun isLoggedIn(): Boolean {
        return authData?.isDeviceLoggedIn() == true
    }

    suspend fun getRemoteConfig(): String? {
        val ws = webSocket ?: return null
        return try {
            val response = ws.sendRequest("GET", "/v2/config")
            if (response.status in 200..299) response.body.toStringUtf8() else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get remote config: ${e.message}")
            null
        }
    }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            val auth = SignalAuthData.load(appContext)
            if (auth != null) {
                authData = auth
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
            val auth = SignalAuthData.load(appContext) ?: run {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            bootSession(auth)
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect — stopping Signal websockets")
        backfillJob?.cancel()
        keyCheckJob?.cancel()
        keyCheckJob = null
        webSocket?.disconnect()
        webSocket = null
        unauthedWebSocket?.disconnect()
        unauthedWebSocket = null
        messageSender = null
        contactManager = null
        contactDiscovery = null
        profileManager = null
        groupManager = null
        _state.value = State.Disconnected("Disconnected")
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Signal session")
        disconnect()
        nameCache.clear()
        scope.launch { SignalAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    // Fix #14: QR auto-refresh with 45s timeout and 6 retries
    fun startProvisioning() {
        _state.value = State.Connecting
        provisioningJob?.cancel()
        provisioningJob = scope.launch {
            var qrRetryCount = 0
            val maxQrRetries = 6

            while (qrRetryCount < maxQrRetries) {
                try {
                    var gotSuccess = false
                    val timeoutJob = launch {
                        delay(45_000L)
                    }

                    val provisionFlow = Provisioning.startProvisioning(appContext)
                    provisionFlow.collect { event ->
                        when (event) {
                            is Provisioning.ProvisioningEvent.QrUrl -> {
                                _state.value = State.AwaitingQrScan(event.url)
                            }
                            is Provisioning.ProvisioningEvent.Success -> {
                                timeoutJob.cancel()
                                gotSuccess = true
                                val data = event.deviceData
                                val auth = SignalAuthData(
                                    aci = data.aci,
                                    pni = data.pni,
                                    deviceId = data.deviceId,
                                    number = data.number,
                                    password = data.password,
                                    aciIdentityKeyPair = data.aciIdentityKeyPair,
                                    pniIdentityKeyPair = data.pniIdentityKeyPair,
                                    aciRegistrationId = data.aciRegistrationId,
                                    pniRegistrationId = data.pniRegistrationId,
                                    masterKey = data.masterKey,
                                    accountEntropyPool = data.accountEntropyPool,
                                    ephemeralBackupKey = data.ephemeralBackupKey,
                                    mediaRootBackupKey = data.mediaRootBackupKey,
                                )
                                auth.save(appContext)
                                authData = auth
                                bootSession(auth)
                                return@collect
                            }
                            is Provisioning.ProvisioningEvent.Error -> {
                                timeoutJob.cancel()
                                Log.e(TAG, "Provisioning error: ${event.message}")
                                _state.value = State.Disconnected("Provisioning failed: ${event.message}")
                                return@collect
                            }
                        }
                    }

                    if (gotSuccess) return@launch

                    // If we got here without success, the flow completed or timed out
                    timeoutJob.cancel()
                    qrRetryCount++
                    if (qrRetryCount >= maxQrRetries) {
                        _state.value = State.Disconnected("Too many QR code refreshes")
                        return@launch
                    }
                    Log.d(TAG, "QR scan timed out, refreshing (attempt $qrRetryCount/$maxQrRetries)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Provisioning attempt failed", e)
                    qrRetryCount++
                    if (qrRetryCount >= maxQrRetries) {
                        _state.value = State.Disconnected("Provisioning failed: ${e.message}")
                        return@launch
                    }
                }
            }
        }
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.textMessage(body, timestamp)
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        return try {
            val ws = webSocket ?: return false
            val pointer = AttachmentManager.upload(ws, bytes, mime, fileName) ?: return false
            val sender = messageSender ?: return false
            val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
            val timestamp = System.currentTimeMillis()
            val dataMessage = SignalServiceProtos.DataMessage.newBuilder()
                .setTimestamp(timestamp)
                .addAttachments(pointer)
            if (!caption.isNullOrBlank()) dataMessage.setBody(caption)
            val content = SignalServiceProtos.Content.newBuilder()
                .setDataMessage(dataMessage.build())
                .build()
            val result = sender.sendMessage(recipientAci, content, timestamp)
            result.success
        } catch (t: Throwable) {
            Log.w(TAG, "sendMedia failed: ${t.message}")
            false
        }
    }

    // Issue #12: Group read receipt timestamps by sender ACI, send per-sender ReceiptMessage
    suspend fun markRead(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        return try {
            val messagesDb = buildMessagesDatabase(appContext)
            val messages = messagesDb.messageDao().observeForConversation(conversationId).first()
            if (messages.isEmpty()) return true

            // Group timestamps by sender ACI
            val timestampsBySender = mutableMapOf<String, MutableList<Long>>()
            for (msg in messages) {
                val senderAci = msg.senderId ?: continue
                // Skip own messages
                if (senderAci == auth.aci) continue
                timestampsBySender.getOrPut(senderAci) { mutableListOf() }.add(msg.timestamp)
            }

            // Send a separate ReceiptMessage per sender
            for ((senderAci, timestamps) in timestampsBySender) {
                val recipientAci = resolveRecipient(senderAci) ?: continue
                val content = ContentBuilders.readReceipt(timestamps)
                sender.sendMessage(recipientAci, content, System.currentTimeMillis())
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed: ${t.message}")
            false
        }
    }

    // Fix #2: deleteThread — add SyncMessage DeleteForMe sync to Signal
    suspend fun deleteThread(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        val aci = extractAci(conversationId) ?: return false

        // Build ConversationIdentifier
        val convId = SignalServiceProtos.ConversationIdentifier.newBuilder()
            .setThreadServiceIdBinary(
                com.google.protobuf.ByteString.copyFrom(MessageSender.uuidToBytes(aci))
            ).build()

        // Query last 5 messages for the anchor
        val mostRecentMessages = try {
            val messagesDb = buildMessagesDatabase(appContext)
            val messages = messagesDb.messageDao().observeForConversation(conversationId).first()
            messages.takeLast(5).mapNotNull { msg ->
                val authorAci = msg.senderId ?: return@mapNotNull null
                SignalServiceProtos.AddressableMessage.newBuilder()
                    .setAuthorServiceIdBinary(
                        com.google.protobuf.ByteString.copyFrom(MessageSender.uuidToBytes(authorAci))
                    )
                    .setSentTimestamp(msg.timestamp)
                    .build()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to query recent messages for delete: ${t.message}")
            emptyList()
        }

        val deleteContent = ContentBuilders.deleteForMeSyncMessage(
            conversationId = convId,
            mostRecentMessages = mostRecentMessages,
            isFullDelete = true,
        )

        // Send to self as sync message
        try {
            sender.sendSelfSyncMessage(deleteContent, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send DeleteForMe sync: ${t.message}")
        }

        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    // Fix #9: Message deletion with 24h time restriction
    suspend fun deleteMessage(
        messageId: String,
        conversationId: String,
        messageTimestamp: Long,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val targetTimestamp = messageId.substringAfterLast('_').toLongOrNull() ?: return false

        // Enforce 24h delete window (matching Go bridge capabilities: DeleteMaxAge = 24h)
        val age = System.currentTimeMillis() - messageTimestamp
        if (age > MESSAGE_DELETE_MAX_AGE_MS) {
            Log.w(TAG, "Cannot delete message older than 24 hours (age=${age}ms)")
            return false
        }

        val content = ContentBuilders.deleteMessage(targetTimestamp)
        val timestamp = System.currentTimeMillis()
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Issue #11: Add messageAuthorAci parameter for correct reaction targeting
    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        add: Boolean,
        messageAuthorAci: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val targetTimestamp = messageId.substringAfterLast('_').toLongOrNull() ?: return false
        val authorAci = messageAuthorAci ?: recipientAci
        val content = ContentBuilders.reactionMessage(emoji, authorAci, targetTimestamp, !add)
        val timestamp = System.currentTimeMillis()
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #10: Group typing support (include GroupIdentifier)
    suspend fun sendTyping(conversationId: String, isTyping: Boolean = true): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val aci = extractAci(conversationId) ?: return false

        // Check if this is a group conversation
        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val groupIdBytes = Base64.decode(groupId, Base64.DEFAULT)
                val content = ContentBuilders.typingMessage(isTyping, System.currentTimeMillis(), groupIdBytes)
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, System.currentTimeMillis())
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(aci) ?: return false
        val content = ContentBuilders.typingMessage(isTyping, System.currentTimeMillis())
        val result = sender.sendMessage(recipientAci, content, System.currentTimeMillis())
        return result.success
    }

    suspend fun sendStopTyping(conversationId: String): Boolean {
        return sendTyping(conversationId, isTyping = false)
    }

    fun fetchMessages(conversationId: String, count: Int = 50) {
        // Signal doesn't support fetching history for linked devices
    }

    suspend fun sendNewThread(recipients: List<String>, body: String): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val sender = messageSender ?: return null
        val recipientAci = resolveRecipient(recipients.first()) ?: return null
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.textMessage(body, timestamp)
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return if (result.success) "${source.idPrefix}:$recipientAci" else null
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        return contactManager?.searchContacts(query) ?: emptyList()
    }

    // Issue #7: Edit messages — wraps EditMessage around a DataMessage with new body
    suspend fun sendEdit(
        conversationId: String,
        originalMessageId: String,
        newBody: String,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val targetTimestamp = originalMessageId.substringAfterLast('_').toLongOrNull() ?: return false
        val editTimestamp = System.currentTimeMillis()
        val content = ContentBuilders.editMessage(targetTimestamp, newBody, editTimestamp)
        val result = sender.sendMessage(recipientAci, content, editTimestamp)
        return result.success
    }

    // Issue #10: Accept message request by sending profileKey to the requester
    suspend fun acceptMessageRequest(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false

        // Send sync message
        val syncContent = ContentBuilders.messageRequestResponseSync(
            threadAci = recipientAci,
            type = SignalServiceProtos.SyncMessage.MessageRequestResponse.Type.ACCEPT,
        )
        try {
            sender.sendSelfSyncMessage(syncContent, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send message request accept sync: ${t.message}")
        }

        // Send profileKey DataMessage to the requester
        val profileKey = contactManager?.getProfileKey(auth.aci)
        val timestamp = System.currentTimeMillis()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setFlags(SignalServiceProtos.DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE)
            .setTimestamp(timestamp)
        if (profileKey != null) {
            dm.setProfileKey(com.google.protobuf.ByteString.copyFrom(profileKey))
        }
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #4: Group metadata changes
    // Issue #8: Real group name change using GroupManager
    suspend fun setGroupName(groupId: String, newName: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.setGroupName(groupId, newName)
    }

    suspend fun setGroupAvatar(groupId: String, avatarBytes: ByteArray): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.uploadGroupAvatar(groupId, avatarBytes)
    }

    suspend fun setGroupDescription(groupId: String, newDescription: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.setGroupDescription(groupId, newDescription)
    }

    // Fix #6: Disappearing messages
    suspend fun setDisappearingTimer(conversationId: String, expirationSeconds: Int): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val aci = extractAci(conversationId) ?: return false
        val groupId = extractGroupIdFromConversation(conversationId)

        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.disappearingTimerMessage(expirationSeconds, timestamp)

        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(aci) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #7: Poll creation
    suspend fun createPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean = false,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.pollCreateMessage(question, options, allowMultiple, timestamp)

        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #7: Poll voting
    suspend fun votePoll(
        conversationId: String,
        targetAuthorAci: String,
        targetTimestamp: Long,
        optionIndexes: List<Int>,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val timestamp = System.currentTimeMillis()
        val authorBytes = MessageSender.uuidToBytes(targetAuthorAci)
        val content = ContentBuilders.pollVoteMessage(authorBytes, targetTimestamp, optionIndexes, timestamp)

        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private suspend fun bootSession(auth: SignalAuthData) {
        _state.value = State.Connecting
        try {
            SignalHttpClient.init(appContext)
            val database = SignalDatabase.getInstance(appContext)
            db = database

            val sessStore = SignalSessionStore(database)
            val idStore = SignalIdentityKeyStore(
                database,
                auth.aciIdentityKeyPair,
                auth.aciRegistrationId,
            )
            val pkStore = SignalPreKeyStore(database)
            val skStore = SignalSenderKeyStore(database)
            sessionStore = sessStore
            identityKeyStore = idStore
            preKeyStore = pkStore
            senderKeyStore = skStore

            val basicAuth = "${auth.aci}.${auth.deviceId}:${auth.password}"

            if (webSocket != null) {
                webSocket?.disconnect()
                webSocket = null
            }
            if (unauthedWebSocket != null) {
                unauthedWebSocket?.disconnect()
                unauthedWebSocket = null
            }

            val ws = SignalWebSocket(
                appContext,
                android.util.Base64.encodeToString(
                    basicAuth.toByteArray(),
                    android.util.Base64.NO_WRAP,
                ),
            )
            ws.connect("wss://chat.signal.org/v1/websocket/")
            webSocket = ws

            val unauthedWs = SignalWebSocket(appContext)
            unauthedWs.connect("wss://chat.signal.org/v1/websocket/")
            unauthedWebSocket = unauthedWs

            val devManager = DeviceManager(ws, sessStore, idStore, pkStore, pkStore, pkStore, skStore)
            val recipientStore = SignalRecipientStore(database)
            val groupStore = SignalGroupStore(database)
            val aciKeyPair = try { IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP)) } catch (_: Exception) { null }
            val pniKeyPair = try { IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP)) } catch (_: Exception) { null }
            val acctRecord = try {
                auth.accountRecord?.let { com.vayunmathur.messages.signal.proto.AccountRecord.parseFrom(it) }
            } catch (_: Exception) { null }
            val sender = MessageSender(ws, sessStore, idStore, pkStore, pkStore, pkStore, skStore, auth.aci, auth.deviceId, devManager, recipientStore, unauthedWs, groupStore, auth.pni, aciKeyPair, pniKeyPair, acctRecord)
            messageSender = sender
            contactManager = ContactManager(recipientStore)
            contactDiscovery = ContactDiscovery(recipientStore, auth.aci, auth.deviceId, auth.password, appContext)
            profileManager = ProfileManager(unauthedWs, recipientStore)
            groupManager = GroupManager(ws, groupStore, recipientStore, auth.aci, auth.pni, auth.password)

            ws.incomingRequestHandler = { request ->
                handleIncomingRequest(request, auth, sessStore, idStore, pkStore, skStore)
            }

            _state.value = State.Connected
            Log.i(TAG, "Connected to Signal")

            scope.launch {
                try {
                    ws.sendRequest(
                        "PUT",
                        "/v1/devices/capabilities",
                        Provisioning.signalCapabilities.toString().toByteArray(),
                    )
                    Log.d(TAG, "Successfully registered capabilities")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to register capabilities: ${t.message}")
                }
            }

            scope.launch {
                try {
                    val aciKeyPair = IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP))
                    val pniKeyPair = IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP))
                    PreKeyManager.generateAndUploadPreKeys(ws, pkStore, aciKeyPair, pniKeyPair)
                } catch (t: Throwable) {
                    Log.w(TAG, "Initial pre-key upload failed: ${t.message}")
                }
            }

            keyCheckJob?.cancel()
            keyCheckJob = scope.launch {
                try {
                    val aciKeyPair = IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP))
                    val pniKeyPair = IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP))
                    PreKeyManager.keyCheckLoop(ws, pkStore, aciKeyPair, pniKeyPair)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Key check loop terminated: ${t.message}")
                    if (t.message?.contains("422") == true) {
                        _state.value = State.Disconnected("Logged out")
                        stop()
                    }
                }
            }

            if (syncContactsOnConnect) {
                kickoffBackfill()
            }

            if (auth.masterKey == null) {
                scope.launch {
                    try {
                        sendStorageMasterKeyRequest()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Storage key sync request failed: ${t.message}")
                    }
                }
            }

            scope.launch {
                ws.connectionEvents.collect { event ->
                    when (event) {
                        is SignalWebSocket.ConnectionEvent.Connected -> {
                            _state.value = State.Connected
                        }
                        is SignalWebSocket.ConnectionEvent.Disconnected -> {
                            _state.value = State.Disconnected(event.reason)
                        }
                        is SignalWebSocket.ConnectionEvent.LoggedOut -> {
                            _state.value = State.Disconnected("Logged out")
                            stop()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootSession failed", e)
            _state.value = State.Disconnected("Boot failed: ${e.message}")
        }
    }

    private fun handleIncomingRequest(
        request: WebSocketProtos.WebSocketRequestMessage,
        auth: SignalAuthData,
        sessStore: SignalSessionStore,
        idStore: SignalIdentityKeyStore,
        pkStore: SignalPreKeyStore,
        skStore: SignalSenderKeyStore,
    ) {
        if (request.verb == "PUT" && request.path == "/api/v1/queue/empty") {
            webSocket?.sendResponse(request.id, 200)
            return
        }
        if (request.verb != "PUT" || request.path != "/api/v1/message") {
            webSocket?.sendResponse(request.id, 200)
            return
        }
        scope.launch {
            try {
                val envelope = SignalServiceProtos.Envelope.parseFrom(request.body)
                val result = EnvelopeDecryptor.decrypt(
                    envelope, sessStore, idStore, pkStore, pkStore, pkStore, skStore,
                    null, auth.aci, auth.deviceId,
                )
                webSocket?.sendResponse(request.id, 200)

                if (result.content != null) {
                    val decrypted = ContentDispatcher.dispatch(
                        result.senderAci, result.senderDeviceId,
                        result.content, result.timestamp, result.serverTimestamp,
                        selfAci = auth.aci,
                    )
                    handleDecryptedMessage(decrypted)

                    if (result.senderAci != auth.aci && result.content.hasDataMessage()) {
                        messageSender?.sendDeliveryReceipt(
                            result.senderAci,
                            listOf(result.content.dataMessage.timestamp),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
                webSocket?.sendResponse(request.id, 200)
            }
        }
    }

    private suspend fun handleDecryptedMessage(msg: DecryptedMessage?) {
        if (msg == null) return
        val chatId = msg.senderAci
        val senderName = nameCache[msg.senderAci] ?: resolveDisplayName(msg.senderAci)

        when (val content = msg.content) {
            is MessageContent.TextMessage -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, content.body, false, msg.timestamp, senderName))
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, content.body, senderName, null, msg.timestamp))
                _events.emit(GMEvent.ConversationUpdate(
                    source = source, conversationId = chatId,
                    peerName = senderName, peerPhone = null, avatarUrl = null,
                    lastPreview = content.body, lastTimestamp = msg.timestamp,
                    unreadCount = 1, conversationType = "Signal",
                ))
            }
            is MessageContent.Attachment -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = content.body ?: "[Attachment]"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName))
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
            }
            is MessageContent.Reaction -> {
                // Reactions are handled by updating the message
            }
            is MessageContent.Delete -> {
                val msgId = "${chatId}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageDeleted(source, msgId))
            }
            // Fix #19: AdminDelete handling
            is MessageContent.AdminDelete -> {
                val authorAci = content.targetAuthorAci ?: msg.senderAci
                val msgId = "${authorAci}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageDeleted(source, msgId))
            }
            is MessageContent.SyncSent -> {
                val destAci = content.destinationAci ?: return
                val syncContent = content.message ?: return
                if (syncContent is MessageContent.TextMessage) {
                    val msgId = "${destAci}_${content.timestamp}"
                    _events.emit(GMEvent.MessageUpdate(source, destAci, msgId, syncContent.body, true, content.timestamp, null))
                    _events.emit(GMEvent.ConversationUpdate(
                        source = source, conversationId = destAci,
                        peerName = resolveDisplayName(destAci), peerPhone = null, avatarUrl = null,
                        lastPreview = syncContent.body, lastTimestamp = content.timestamp,
                        unreadCount = 0, conversationType = "Signal",
                    ))
                }
            }
            is MessageContent.Typing -> {}
            is MessageContent.ReadReceipt -> {}
            // Fix #16: ViewedReceipt handling
            is MessageContent.ViewedReceipt -> {
                Log.d(TAG, "Received viewed receipt for ${content.timestamps.size} messages")
            }
            is MessageContent.DeliveryReceipt -> {}
            is MessageContent.Call -> {}
            is MessageContent.Edit -> {
                val msgId = "${chatId}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, content.newBody, false, msg.timestamp, senderName))
            }
            // Fix #11: SyncRead handler — forward as read receipts
            is MessageContent.SyncRead -> {
                for ((senderAci, timestamp) in content.messages) {
                    _events.emit(GMEvent.ReadReceipt(source, conversationId = senderAci, messageId = "${senderAci}_${timestamp}"))
                }
                Log.d(TAG, "Processed ${content.messages.size} sync read messages")
            }
            is MessageContent.SyncKeys -> {
                val masterKey = content.masterKey
                    ?: content.accountEntropyPool?.let { aep ->
                        try {
                            org.signal.libsignal.messagebackup.AccountEntropyPool.deriveSvrKey(aep)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to derive master key from account entropy pool", e)
                            null
                        }
                    }
                if (masterKey != null) {
                    val auth = authData ?: return
                    val updated = auth.copy(masterKey = Base64.encodeToString(masterKey, Base64.NO_WRAP))
                    updated.save(appContext)
                    authData = updated
                    Log.i(TAG, "Received and saved master key from sync")
                }
            }
            is MessageContent.SyncFetchLatest -> {
                Log.d(TAG, "Received fetch latest: ${content.type}")
            }
            // Fix #12: SyncDeleteForMe handler — process conversation and message deletes
            is MessageContent.SyncDeleteForMe -> {
                for (convDelete in content.conversationDeletes) {
                    val convAci = convDelete.conversationId.threadAci
                    if (convDelete.isFullDelete && convAci != null) {
                        _events.emit(GMEvent.ConversationDeleted(source, "${source.idPrefix}:$convAci"))
                        Log.d(TAG, "SyncDeleteForMe: deleted conversation for $convAci")
                    }
                    // Issue #15: Handle mostRecentMessages variant — delete individual messages
                    for (addrMsg in convDelete.mostRecentMessages) {
                        val authorAci = addrMsg.authorAci ?: continue
                        val delMsgId = "${authorAci}_${addrMsg.sentTimestamp}"
                        _events.emit(GMEvent.MessageDeleted(source, delMsgId))
                    }
                    for (addrMsg in convDelete.mostRecentNonExpiringMessages) {
                        val authorAci = addrMsg.authorAci ?: continue
                        val delMsgId = "${authorAci}_${addrMsg.sentTimestamp}"
                        _events.emit(GMEvent.MessageDeleted(source, delMsgId))
                    }
                }
                for (msgDelete in content.messageDeletes) {
                    for (addrMsg in msgDelete.messages) {
                        val authorAci = addrMsg.authorAci ?: continue
                        val delMsgId = "${authorAci}_${addrMsg.sentTimestamp}"
                        _events.emit(GMEvent.MessageDeleted(source, delMsgId))
                    }
                }
            }
            // Fix #13: SyncMessageRequestResponse handler
            is MessageContent.SyncMessageRequestResponse -> {
                val threadAci = content.threadAci
                if (content.type == "ACCEPT" && threadAci != null) {
                    Log.d(TAG, "Message request accepted for $threadAci")
                } else if (content.type == "DELETE" && threadAci != null) {
                    _events.emit(GMEvent.ConversationDeleted(source, "${source.idPrefix}:$threadAci"))
                    Log.d(TAG, "Message request deleted for $threadAci")
                }
            }
            // Fix #15: Contact sync blob processing
            is MessageContent.SyncContacts -> {
                try {
                    val (contacts, avatars) = ContactManager.unmarshalContactDetailsMessages(content.blob)
                    contactManager?.handleContactSync(contacts, avatars)
                    Log.d(TAG, "Processed contact sync with ${contacts.size} contacts")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process contact sync blob", e)
                }
            }
            is MessageContent.Sticker -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = content.emoji ?: "[Sticker]"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName))
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
            }
            is MessageContent.ProfileKeyUpdate -> {}
            // Fix #3: GroupChange processing
            is MessageContent.GroupChange -> {
                val gId = content.groupId
                if (gId != null) {
                    Log.d(TAG, "Received group change for $gId at revision ${content.revision}")
                    groupManager?.invalidateCachedGroup(gId)
                    val masterKey = groupManager?.let { gm ->
                        val stored = db?.groupDao()?.get(gId)
                        stored?.masterKey
                    }
                    if (masterKey != null) {
                        groupManager?.fetchGroup(gId, masterKey)
                    }
                }
            }
            // Fix #17: GroupCallUpdate handling
            is MessageContent.GroupCallUpdate -> {
                val gId = content.groupId
                if (gId != null && content.eraId != null) {
                    val isNew = groupManager?.updateActiveCall(gId, content.eraId) ?: false
                    if (isNew) {
                        val msgId = "${gId}_${msg.timestamp}"
                        _events.emit(GMEvent.IncomingMessage(source, gId, msgId, "Group call started", senderName, null, msg.timestamp))
                    }
                }
                Log.d(TAG, "GroupCallUpdate: eraId=${content.eraId}, groupId=${content.groupId}")
            }
            // Fix #7: Poll messages
            is MessageContent.PollCreate -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val optionsList = content.options.joinToString(", ")
                val body = "Poll: ${content.question}\nOptions: $optionsList"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName))
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
            }
            is MessageContent.PollVote -> {
                Log.d(TAG, "Received poll vote for timestamp ${content.targetTimestamp}")
            }
            // Fix #6: Disappearing timer update
            is MessageContent.ExpirationTimerUpdate -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = if (content.expirationSeconds > 0) {
                    "Disappearing messages set to ${content.expirationSeconds}s"
                } else {
                    "Disappearing messages turned off"
                }
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
            }
            // Fix #18: Payment/GiftBadge/Contact placeholder text
            is MessageContent.Payment -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, "Payment message (unsupported)", senderName, null, msg.timestamp))
            }
            is MessageContent.GiftBadge -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, "Gift badge (unsupported)", senderName, null, msg.timestamp))
            }
            is MessageContent.ContactCard -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, "Contact card (unsupported)", senderName, null, msg.timestamp))
            }
            is MessageContent.Unknown -> {
                Log.d(TAG, "Unknown content: ${content.description}")
            }
        }
    }

    private suspend fun resolveDisplayName(aci: String): String? {
        nameCache[aci]?.let { return it }
        val name = contactManager?.getDisplayName(aci)
        if (name != null) nameCache[aci] = name
        return name
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "kickoffBackfill — Signal backfill is contact-sync driven")
            _events.emit(GMEvent.ConversationUpdate(
                source = source, conversationId = "_backfill_sentinel",
                peerName = null, peerPhone = null, avatarUrl = null,
                lastPreview = null, lastTimestamp = 0, unreadCount = 0,
            ))

            val sender = messageSender ?: return@launch
            try {
                val syncRequest = SignalServiceProtos.SyncMessage.Request.newBuilder()
                    .setType(SignalServiceProtos.SyncMessage.Request.Type.CONTACTS)
                    .build()
                val syncMessage = SignalServiceProtos.SyncMessage.newBuilder()
                    .setRequest(syncRequest)
                    .build()
                val content = SignalServiceProtos.Content.newBuilder()
                    .setSyncMessage(syncMessage)
                    .build()
                val auth = authData ?: return@launch
                sender.sendMessage(auth.aci, content, System.currentTimeMillis())
                Log.d(TAG, "Sent contacts sync request to primary device")
            } catch (t: Throwable) {
                Log.w(TAG, "Contacts sync request failed: ${t.message}")
            }
        }
    }

    private suspend fun sendStorageMasterKeyRequest() {
        val sender = messageSender ?: return
        val auth = authData ?: return
        val syncRequest = SignalServiceProtos.SyncMessage.Request.newBuilder()
            .setType(SignalServiceProtos.SyncMessage.Request.Type.KEYS)
            .build()
        val syncMessage = SignalServiceProtos.SyncMessage.newBuilder()
            .setRequest(syncRequest)
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(syncMessage)
            .build()
        sender.sendMessage(auth.aci, content, System.currentTimeMillis())
        Log.d(TAG, "Sent storage master key request to primary device")
    }

    private fun extractAci(conversationId: String): String? {
        return conversationId.substringAfter(':', conversationId).takeIf { it.isNotBlank() }
    }

    private fun extractGroupIdFromConversation(conversationId: String): String? {
        // Group conversation IDs are prefixed with "group:"
        if (conversationId.startsWith("group:")) {
            return conversationId.substringAfter("group:")
        }
        return null
    }

    private suspend fun resolveRecipient(identifier: String): String? {
        if (!identifier.startsWith("+")) return identifier
        val discovery = contactDiscovery ?: return null
        return discovery.resolveE164(identifier)
    }
}
