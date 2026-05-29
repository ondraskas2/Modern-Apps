package com.vayunmathur.passwords.util

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.data.Password
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Passwords app.
 *
 * Owns:
 *  - Central TOTP ticker as a single shared [StateFlow] (replaces per-row
 *    [androidx.compose.runtime.LaunchedEffect] that ticked once per row).
 *  - Bitwarden-style CSV import (content-resolver read + parse + per-row upsert).
 *  - Edit-form draft state for [Password] (decoupled from composable lifetime).
 *  - Copy-to-clipboard actions, with a [SharedFlow] for one-shot "copied" events.
 *
 * The shared [DatabaseViewModel] is injected so this VM can persist rows
 * without leaking Compose state. Composables continue to use
 * [DatabaseViewModel.getState] for per-row reads.
 */
class PasswordsViewModel(
    application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : AndroidViewModel(application) {

    // -- TOTP ticker ------------------------------------------------------

    /**
     * Wall-clock millis, ticked once per second. The flow is shared across
     * every TOTP row so we don't allocate a coroutine per row. It is
     * stopped via [SharingStarted.WhileSubscribed] when no composable is
     * observing, matching the previous per-row `LaunchedEffect` behavior
     * which cancelled on leaving composition.
     */
    val tickerFlow: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(1000),
        System.currentTimeMillis(),
    )

    // -- Clipboard --------------------------------------------------------

    private val _copyEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits a short label (e.g. "Password copied") for snackbar feedback. */
    val copyEvents: SharedFlow<String> = _copyEvents.asSharedFlow()

    fun copyToClipboard(label: String, text: String, feedback: String? = null) {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (feedback != null) {
            viewModelScope.launch { _copyEvents.emit(feedback) }
        }
    }

    // -- Edit-form draft --------------------------------------------------

    private val _draft = MutableStateFlow<Password?>(null)
    /** Currently-edited password draft, or null if no edit in progress. */
    val draft: StateFlow<Password?> = _draft.asStateFlow()

    /**
     * Initialize the draft from the persisted [seed] the first time the edit
     * page is shown for a given id. Subsequent calls with the same id are
     * ignored so user edits are not clobbered.
     */
    fun initDraft(seed: Password) {
        val current = _draft.value
        if (current == null || current.id != seed.id) {
            _draft.value = seed
        }
    }

    fun updateDraft(transform: (Password) -> Password) {
        _draft.value = _draft.value?.let(transform)
    }

    fun clearDraft() {
        _draft.value = null
    }

    /**
     * Persists the current draft. For new rows, the assigned id is reported
     * via [onSaved]. Clears the draft once enqueued.
     */
    fun saveDraft(onSaved: ((Long) -> Unit)? = null) {
        val current = _draft.value ?: return
        if (onSaved != null) {
            databaseViewModel.upsertAsync(current, onSaved)
        } else {
            databaseViewModel.upsertAsync(current)
        }
        _draft.value = null
    }

    // -- CSV import -------------------------------------------------------

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun dismissImportMessage() {
        _importMessage.value = null
    }

    /**
     * Reads a Bitwarden-style CSV at [uri] off the main thread, parses it,
     * and upserts each row via the shared [DatabaseViewModel]. Reports
     * progress via [importing] and a terminal status via [importMessage].
     */
    fun importCsv(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _importing.value = true
            _importMessage.value = null
            try {
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                    // Persistable permission is best-effort.
                }
                val result = withContext(Dispatchers.IO) {
                    importBitwardenCsvFromUri(ctx.contentResolver, uri)
                }
                _importMessage.value =
                    "Imported ${result.inserted} rows, skipped ${result.skipped} rows"
            } catch (e: Exception) {
                _importMessage.value = "Import failed: ${e.message}"
            } finally {
                _importing.value = false
            }
        }
    }

    private data class ImportResult(val inserted: Int, val skipped: Int)

    private fun importBitwardenCsvFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
    ): ImportResult {
        val inputStream = try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening input stream for URI: $uri", e)
            null
        } ?: throw Exception("Unable to open selected file")

        val rows = try {
            val reader = inputStream.bufferedReader()
            val list = mutableListOf<List<String>>()
            var line = reader.readLine()
            while (line != null) {
                val row = mutableListOf<String>()
                var current = StringBuilder()
                var inQuotes = false
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    if (c == '"') {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    } else if (c == ',' && !inQuotes) {
                        row.add(current.toString())
                        current = StringBuilder()
                    } else {
                        current.append(c)
                    }
                    i++
                }
                row.add(current.toString())
                list.add(row)
                line = reader.readLine()
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CSV from input stream", e)
            emptyList<List<String>>()
        }
        if (rows.isEmpty()) return ImportResult(0, 0)

        val header = rows.first().map { it.trim().lowercase() }
        val nameIdx = header.indexOf("name")
        val loginUsernameIdx = header.indexOf("login_username")
            .let { if (it >= 0) it else header.indexOf("username") }
        val loginPasswordIdx = header.indexOf("login_password")
            .let { if (it >= 0) it else header.indexOf("password") }
        val loginUriIdx = header.indexOf("login_uri")
            .let { if (it >= 0) it else header.indexOf("uri") }
        val loginTotpIdx = header.indexOf("login_totp")
            .let { if (it >= 0) it else header.indexOf("totp") }

        var inserted = 0
        var skipped = 0

        for (row in rows.drop(1)) {
            try {
                val name = if (nameIdx >= 0 && nameIdx < row.size) row[nameIdx] else ""
                val username = if (loginUsernameIdx >= 0 && loginUsernameIdx < row.size)
                    row[loginUsernameIdx] else ""
                val password = if (loginPasswordIdx >= 0 && loginPasswordIdx < row.size)
                    row[loginPasswordIdx] else ""
                val uriField = if (loginUriIdx >= 0 && loginUriIdx < row.size)
                    row[loginUriIdx] else ""
                val totp = if (loginTotpIdx >= 0 && loginTotpIdx < row.size)
                    row[loginTotpIdx] else null

                val websites = uriField.split(';', '\n', '\r')
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                databaseViewModel.upsertAsync(
                    Password(
                        name = name,
                        userId = username,
                        password = password,
                        totpSecret = totp,
                        websites = websites,
                    ),
                )
                inserted++
            } catch (_: Exception) {
                skipped++
            }
        }

        return ImportResult(inserted, skipped)
    }

    companion object {
        private const val TAG = "PasswordsViewModel"
    }
}

/** Factory for constructing [PasswordsViewModel] with the shared [DatabaseViewModel]. */
class PasswordsViewModelFactory(
    private val application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PasswordsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return PasswordsViewModel(application, databaseViewModel) as T
    }
}
