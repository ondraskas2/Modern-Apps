package com.vayunmathur.openassistant.util

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.openassistant.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock

/**
 * ViewModel for the OpenAssistant app.
 *
 * Owns:
 *  - filtered chat-message stream per conversation
 *  - audio recorder + recording-state lifecycle (mic permission still lives in
 *    composables since it uses [androidx.activity.compose.rememberLauncherForActivityResult])
 *  - inference-service lifecycle: pre-warm on init and dispatch per-message
 *    inference requests via [requestInference]
 *  - one-time on-disk migration of the legacy gemma4 model file
 *
 * The shared [DatabaseViewModel] is injected so this VM can derive flows from
 * the Room-backed message table without owning the database itself.
 */
class AssistantViewModel(
    application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : AndroidViewModel(application) {

    private val _isRecording = MutableStateFlow(false)
    /** True while the [WavRecorder] is actively capturing microphone audio. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedAudioPath = MutableStateFlow<String?>(null)
    /** Absolute path of the most recent recording, or null if none is pending send. */
    val recordedAudioPath: StateFlow<String?> = _recordedAudioPath.asStateFlow()

    private var audioRecorder: WavRecorder? = null

    init {
        cleanupLegacyModelFile()
        // Pre-warm the inference engine so the first user prompt is responsive.
        val context = getApplication<Application>()
        context.startService(Intent(context, InferenceService::class.java))
    }

    /**
     * Returns a flow of messages belonging to [conversationId], sorted by
     * timestamp. Derived from the shared [DatabaseViewModel] message table.
     */
    fun messagesFor(conversationId: Long): Flow<List<Message>> =
        databaseViewModel.data<Message>().map { all ->
            all.filter { it.conversationId == conversationId }.sortedBy { it.timestamp }
        }

    /**
     * Starts a new microphone capture into the app cache. Caller is expected
     * to have already obtained [android.Manifest.permission.RECORD_AUDIO];
     * if recording fails to initialize, [isRecording] stays false.
     */
    fun startRecording() {
        if (_isRecording.value) return
        val context = getApplication<Application>()
        val file = File(context.cacheDir, "recording_${Clock.System.now().toEpochMilliseconds()}.wav")
        try {
            audioRecorder = WavRecorder(context, file, viewModelScope).apply { start() }
            _recordedAudioPath.value = file.absolutePath
            _isRecording.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            audioRecorder = null
            _recordedAudioPath.value = null
            _isRecording.value = false
        }
    }

    /** Stops the current recording (if any) but keeps [recordedAudioPath] for send. */
    fun stopRecording() {
        audioRecorder?.stop()
        audioRecorder = null
        _isRecording.value = false
    }

    /** Stops recording and discards the pending audio file path. */
    fun cancelRecording() {
        stopRecording()
        _recordedAudioPath.value = null
    }

    /** Clears the recorded-audio reference without stopping a live recording. */
    fun consumeRecordedAudio() {
        _recordedAudioPath.value = null
    }

    /**
     * Dispatches a standard inference request to [InferenceService] for the
     * given conversation. The service owns its own queue, history fetch, and
     * streaming response writes back to the database.
     */
    fun requestInference(
        conversationId: Long,
        userText: String,
        imagePaths: List<String>,
        audioPath: String?,
    ) {
        val context = getApplication<Application>()
        context.startService(Intent(context, InferenceService::class.java).apply {
            putExtra("conversation_id", conversationId)
            putExtra("user_text", userText)
            putExtra("image_paths", imagePaths.toTypedArray())
            putExtra("audio_path", audioPath)
        })
    }

    private fun cleanupLegacyModelFile() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val externalDir = context.getExternalFilesDir(null) ?: return@launch
                // Every model file we no longer use. Add to this list when the
                // active model changes (e.g. gemma4-4b → gemma4-2b) so users
                // don't keep stale gigabytes on disk and so the next launch
                // re-triggers the downloader for the new file.
                val legacyModelFiles = listOf("gemma4.litertlm", "gemma4-4b.litertlm")
                var removedAny = false
                for (name in legacyModelFiles) {
                    val f = File(externalDir, name)
                    if (f.exists() && f.delete()) {
                        Log.i(TAG, "Deleted legacy model file $name")
                        removedAny = true
                    }
                }
                if (removedAny) {
                    // Forces InitialDownloadChecker to re-evaluate which files
                    // need downloading on next composition.
                    DataStoreUtils.getInstance(context).setBoolean("dbSetupComplete", false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up legacy model file", e)
            }
        }
    }

    override fun onCleared() {
        audioRecorder?.stop()
        audioRecorder = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "AssistantViewModel"
    }
}

/** Factory for constructing [AssistantViewModel] with the shared [DatabaseViewModel]. */
class AssistantViewModelFactory(
    private val application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return AssistantViewModel(application, databaseViewModel) as T
    }
}
