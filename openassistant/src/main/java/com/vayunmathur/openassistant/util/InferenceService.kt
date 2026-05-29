package com.vayunmathur.openassistant.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.litertlm.*
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.SecureResultReceiver
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.File
import kotlin.time.Clock
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.Memory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.vayunmathur.openassistant.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class InferenceService : Service() {

    companion object {
        var newTitle: String? = null
        var halt: Boolean = false
    }

    private sealed class InferenceJob {
        data class Intent(
            val userText: String,
            val imagePaths: Array<String>,
            val schema: String,
            val receiver: ResultReceiver,
            val enqueuedTime: Long = System.currentTimeMillis()
        ) : InferenceJob()

        data class Standard(
            val conversationId: Long,
            val userText: String,
            val imagePaths: Array<String>,
            val audioPath: String?
        ) : InferenceJob()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standardQueue = Channel<InferenceJob.Standard>(Channel.UNLIMITED)
    private val intentQueue = Channel<InferenceJob.Intent>(Channel.UNLIMITED)
    
    private var engine: Engine? = null
    private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConversationId: Long = -1L

    val db by lazy { buildDatabase<AppDatabase>(migrations = AppDatabase.MIGRATIONS) }
    val viewModel by lazy { DatabaseViewModel(db, Conversation::class to db.conversationDao(), Message::class to db.messageDao(), Memory::class to db.memoryDao()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundTask()
        serviceScope.launch {
            try {
                ensureEngineInitialized()
            } catch (e: Exception) {
                Log.e("InferenceService", "Error pre-warming engine", e)
            }
        }
        serviceScope.launch {
            Log.d("InferenceService", "Starting job queue processor loop")
            while (isActive) {
                try {
                    // Prioritize standardQueue
                    val standardJob = standardQueue.tryReceive().getOrNull()
                    if (standardJob != null) {
                        executeStandardInference(standardJob)
                        continue
                    }

                    // Check intentQueue if standardQueue is empty
                    val intentJob = intentQueue.tryReceive().getOrNull()
                    if (intentJob != null) {
                        val now = System.currentTimeMillis()
                        if (now - intentJob.enqueuedTime > 45000) {
                            Log.w("InferenceService", "Intent job expired in queue, discarding.")
                            intentJob.receiver.send(-1, Bundle().apply { putString("error", "Request expired in queue") })
                        } else {
                            executeIntentInference(intentJob)
                        }
                        continue
                    }

                    // If both empty, wait for either (with priority to standard)
                    select<Unit> {
                        standardQueue.onReceive { job: InferenceJob.Standard ->
                            executeStandardInference(job)
                        }
                        intentQueue.onReceive { job: InferenceJob.Intent ->
                            val now = System.currentTimeMillis()
                            if (now - job.enqueuedTime > 45000) {
                                Log.w("InferenceService", "Intent job expired in queue, discarding.")
                                job.receiver.send(-1, Bundle().apply { putString("error", "Request expired in queue") })
                            } else {
                                executeIntentInference(job)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("InferenceService", "Critical error in job processor loop", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("InferenceService", "onStartCommand received intent")
        intent?.setExtrasClassLoader(SecureResultReceiver::class.java.classLoader)
        
        val conversationId = intent?.getLongExtra("conversation_id", -1L) ?: -1L
        val userText = intent?.getStringExtra("user_text") ?: ""
        val audioPath = intent?.getStringExtra("audio_path")
        val schema = intent?.getStringExtra("schema")
        val receiver = intent?.getParcelableExtra<ResultReceiver>("RECEIVER")

        val imageUris = intent?.getParcelableArrayListExtra<Uri>("image_uris")
        val imagePathsFromUris = imageUris?.mapNotNull { uri ->
            copyUriToFile(this, uri)?.absolutePath
        }?.toTypedArray() ?: emptyArray()

        val imagePaths = (intent?.getStringArrayExtra("image_paths") ?: emptyArray()) + imagePathsFromUris

        if (receiver != null && schema != null) {
            Log.i("InferenceService", "Queueing Intent Inference request")
            intentQueue.trySend(InferenceJob.Intent(userText, imagePaths, schema, receiver))
        } else if (conversationId != -1L) {
            Log.d("InferenceService", "Queueing standard inference for conversation: $conversationId")
            standardQueue.trySend(InferenceJob.Standard(conversationId, userText, imagePaths, audioPath))
        }

        return START_STICKY
    }

    private fun startForegroundTask() {
        val channelId = "inference_service"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Inference Service", NotificationManager.IMPORTANCE_LOW)
        manager?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OpenAssistant")
            .setContentText("Processing AI inference...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private suspend fun executeIntentInference(job: InferenceJob.Intent) {
        try {
            Log.d("InferenceService", "Executing Intent Inference")
            ensureEngineInitialized()
            
            currentConversation?.close()
            currentConversation = null
            delay(100) 

            setupIntentConversation(job.schema)
            
            withTimeout(45000) {
                runIntentInferenceLoop(job.userText, job.imagePaths, job.schema, job.receiver)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("InferenceService", "Intent inference timed out after 45 seconds")
            job.receiver.send(-1, Bundle().apply { putString("error", "Inference timed out") })
        } catch (e: CancellationException) {
            if (e.message != "HALT") throw e
            Log.i("InferenceService", "Intent inference halted successfully via schema match.")
        } catch (e: Exception) {
            Log.e("InferenceService", "Error during intent inference", e)
            job.receiver.send(-1, Bundle().apply { putString("error", e.localizedMessage ?: "AI engine failed") })
        } finally {
            currentConversation?.close()
            currentConversation = null
            currentConversationId = -1L
        }
    }

    private suspend fun executeStandardInference(job: InferenceJob.Standard) {
        try {
            Log.d("InferenceService", "Executing Standard Inference for conversation ${job.conversationId}")
            ensureEngineInitialized()
            
            if (currentConversationId != job.conversationId || currentConversation == null || !currentConversation!!.isAlive) {
                currentConversation?.close()
                currentConversation = null
                delay(100)

                val history = fetchHistoryFromDb(job.conversationId)
                    .filter { it.text != job.userText || it.timestamp < Clock.System.now().toEpochMilliseconds() - 1000 }
                setupConversation(job.conversationId, history)
            }

            runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
        } catch (e: Exception) {
            Log.e("InferenceService", "Error during standard inference", e)
            upsertMessageToDb(Message(
                conversationId = job.conversationId,
                text = getString(R.string.error_prefix, e.localizedMessage ?: ""),
                role = "assistant",
                timestamp = Clock.System.now().toEpochMilliseconds()
            ))
        }
    }

    private fun setupIntentConversation(schema: String) {
        val systemPrompt = """
            You are a highly specialized data extraction engine.
            Your sole purpose is to analyze the provided images/text and output a SINGLE valid JSON object that adheres STRICTLY to the provided JSON schema.
            
            EXTREMELY IMPORTANT:
            1. DO NOT respond with any conversational text.
            2. DO NOT include any preamble, explanation, or postscript.
            3. Output ONLY the raw JSON object.
            4. Ensure all keys and values are properly quoted.
            
            SCHEMA:
            $schema
            
            Start extraction immediately.
            """.trimIndent()

        currentConversation = engine?.createConversation(ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = emptyList(),
            automaticToolCalling = false,
        ))
        currentConversationId = -2L // Special ID for intent inference
    }

    private suspend fun runIntentInferenceLoop(
        userText: String,
        imagePaths: Array<String>,
        schema: String,
        receiver: ResultReceiver
    ) {
        val conv = currentConversation ?: return

        val initialContents = mutableListOf<Content>()
        imagePaths.forEach { path -> initialContents.add(Content.ImageFile(path)) }
        if (userText.isNotBlank()) { initialContents.add(Content.Text(userText)) }
        
        val nextMessage = com.google.ai.edge.litertlm.Message.user(Contents.of(initialContents))

        var fullResponseText = ""
        Log.d("InferenceService", "Sending intent inference request (Streaming mode for safe interruption)")
        
        val stream = conv.sendMessageAsync(nextMessage)
        
        stream.collect { chunk ->
            val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            fullResponseText += chunkText
            
            // Try to extract JSON and check if it matches schema
            val jsonCandidate = tryExtractLargestJson(fullResponseText)
            if (jsonCandidate != null) {
                val validationError = JsonSchemaValidator.validateJsonAgainstSchema(jsonCandidate, schema)
                if (validationError == null) {
                    Log.i("InferenceService", "Valid JSON extracted and verified against schema. Halting.")
                    receiver.send(0, Bundle().apply { putString("json_result", jsonCandidate) })
                    throw CancellationException("HALT")
                }
            }
        }

        // If it finished without tryExtractLargestJson triggering HALT (e.g. at the very end)
        val finalJson = tryExtractLargestJson(fullResponseText)
        if (finalJson != null) {
            val validationError = JsonSchemaValidator.validateJsonAgainstSchema(finalJson, schema)
            if (validationError == null) {
                receiver.send(0, Bundle().apply { putString("json_result", finalJson) })
                Log.d("InferenceService", "AI produced output: $finalJson")
                return
            }
        }

        Log.e("InferenceService", "AI finished generation without providing a schema-matching JSON.")
        receiver.send(-1, Bundle().apply { putString("error", "AI failed to return valid JSON matching the schema.") })
    }

    private fun tryExtractLargestJson(text: String): String? {
        var start = text.indexOf('{')
        while (start != -1) {
            var end = text.lastIndexOf('}')
            while (end != -1 && end > start) {
                val candidate = text.substring(start, end + 1)
                try {
                    Json.parseToJsonElement(candidate)
                    return candidate
                } catch (e: Exception) {
                    end = text.lastIndexOf('}', end - 1)
                }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private suspend fun ensureEngineInitialized() {
        if (engine != null) return

        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = true

        val modelFile = File(applicationContext.getExternalFilesDir(null)!!, "gemma4-2b.litertlm")
        if (!modelFile.exists()) throw Exception("Model file missing at ${modelFile.absolutePath}")

        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(), 
            cacheDir = applicationContext.cacheDir.absolutePath,
        )
        
        val newEngine = Engine(config)
        withContext(Dispatchers.IO) {
            newEngine.initialize()
        }
        engine = newEngine
    }

    private fun setupConversation(id: Long, history: List<Message>) {
        val systemPrompt = """
            You are a helpful Android assistant.
            On the first request the user sends to you, you MUST define a title for the conversation. You may optionally change the title if the topic of conversation changes sufficiently.
            
            You have a memory feature. Use it to provide a personalized and consistent experience:
            - Whenever the user shares information that might be useful in future conversations (e.g., their name, preferences, family details, or important facts), use 'add_to_memory' to store it.
            - Whenever the user asks a question or makes a request where past context might be relevant, use 'get_memories' to retrieve stored information.
            - If a stored memory is no longer accurate or requested to be forgotten, use 'remove_memory'.
            """.trimIndent()

        val initialMessages = history.map { msg ->
            when (msg.role) {
                "user" -> com.google.ai.edge.litertlm.Message.user(Contents.of(msg.text))
                "assistant" -> com.google.ai.edge.litertlm.Message.model(Contents.of(msg.text))
                else -> com.google.ai.edge.litertlm.Message.user(Contents.of(msg.text))
            }
        }

        currentConversation = engine?.createConversation(ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = initialMessages,
            tools = listOf(tool(AssistantToolSet(applicationContext, viewModel, id))),
            automaticToolCalling = true,
        ))
        currentConversationId = id
    }

    private suspend fun runInferenceLoop(
        conversationId: Long, 
        userText: String, 
        imagePaths: Array<String>, 
        audioPath: String?
    ) {
        val conv = currentConversation ?: return
        
        val aiMsgId = upsertMessageToDb(Message(
            conversationId = conversationId,
            text = "...",
            role = "assistant",
            timestamp = Clock.System.now().toEpochMilliseconds()
        ))

        var fullResponseText = ""
        var displayedText = ""
        val contents = mutableListOf<Content>()
        imagePaths.forEach { path -> contents.add(Content.ImageFile(path)) }
        audioPath?.let { if (File(it).exists()) contents.add(Content.AudioFile(it)) }
        if (userText.isNotBlank()) contents.add(Content.Text(userText))

        val stream = conv.sendMessageAsync(com.google.ai.edge.litertlm.Message.user(Contents.of(contents)))

        stream.catch { e ->
            Log.d("InferenceService", "Caught inference error: ${e::class.simpleName}", e)
            if (e is MissingAppException || e is StopInferenceException || e.cause is StopInferenceException || halt) {
                halt = false
                viewModel.delete(viewModel.get<Message>(aiMsgId))
            } else {
                updateMessageInDb(aiMsgId, getString(R.string.error_prefix, e.message ?: ""))
            }
        }.collect { chunk ->
            if (halt) {
                halt = false
                viewModel.delete(viewModel.get<Message>(aiMsgId))
                throw CancellationException("HALT")
            }
            val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            fullResponseText += chunkText
            displayedText += chunkText

            if(newTitle != null) {
                updateTitleInDb(conversationId, newTitle!!)
                newTitle = null
            }

            if (displayedText.isNotBlank()) {
                updateMessageInDb(aiMsgId, displayedText)
            }
        }
    }

    private suspend fun fetchHistoryFromDb(id: Long): List<Message> = viewModel.getAll<Message>().filter { it.conversationId == id }
    private suspend fun upsertMessageToDb(msg: Message): Long = viewModel.upsert(msg)
    private suspend fun updateMessageInDb(id: Long, text: String) {
        val newMsg = viewModel.get<Message>(id).copy(text = text)
        upsertMessageToDb(newMsg)
    }
    private suspend fun updateTitleInDb(id: Long, title: String) {
        val oldConversation = viewModel.get<Conversation>(id)
        if (oldConversation != null) {
            viewModel.upsert(oldConversation.copy(title = title))
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        currentConversation?.close()
        engine?.close()
        super.onDestroy()
    }
}
