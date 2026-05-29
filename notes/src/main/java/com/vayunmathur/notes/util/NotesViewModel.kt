package com.vayunmathur.notes.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.parseMarkdown
import com.vayunmathur.notes.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for the Notes app.
 *
 * Owns:
 *  - file import / drop handling (content-resolver + DB upsert)
 *  - share-URI generation (cache file write + FileProvider URI)
 *  - parsed-markdown cache (process-wide, keyed by content + search context)
 *
 * The shared [DatabaseViewModel] is injected so this VM can persist imported notes
 * without leaking Compose state. Composables continue to use [DatabaseViewModel.getEditable]
 * for per-note editing.
 */
class NotesViewModel(
    application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : AndroidViewModel(application) {

    private val _shareUris = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    /** Emits a URI for a share intent each time [requestShare] completes. */
    val shareUris: SharedFlow<Uri> = _shareUris.asSharedFlow()

    private data class ParsedKey(
        val content: String,
        val searchQuery: String,
        val searchIndex: Int,
    )

    // Simple LRU cache for parsed markdown AnnotatedStrings. Capped to a small
    // size to avoid retaining every note ever opened in memory. The current note
    // is hot, and switching back and forth between a few notes stays cached.
    private val parsedCache = object : LinkedHashMap<ParsedKey, AnnotatedString>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ParsedKey, AnnotatedString>,
        ): Boolean = size > 32
    }

    /**
     * Returns the cached parsed AnnotatedString for [content] using the
     * "display" parameters (no markers, no soft-wrap, no preprocessing).
     */
    @Synchronized
    fun parseDisplay(
        content: String,
        searchQuery: String = "",
        searchIndex: Int = -1,
    ): AnnotatedString {
        val key = ParsedKey(content, searchQuery, searchIndex)
        parsedCache[key]?.let { return it }
        val parsed = parseMarkdown(
            content,
            showMarkers = false,
            process = false,
            softWrap = false,
            searchQuery = searchQuery,
            searchIndex = searchIndex,
        )
        parsedCache[key] = parsed
        return parsed
    }

    /** Counts case-insensitive occurrences of [searchText] in the parsed text of [content]. */
    fun searchResultsCount(content: String, searchText: String): Int {
        if (searchText.isEmpty()) return 0
        val text = parseDisplay(content).text.lowercase()
        val q = searchText.lowercase()
        var count = 0
        var idx = text.indexOf(q)
        while (idx >= 0) {
            count++
            idx = text.indexOf(q, idx + q.length)
        }
        return count
    }

    /**
     * Reads each [uri] off the main thread and upserts it as a new [Note] via
     * the shared [DatabaseViewModel]. Errors are logged per-file and do not
     * abort the batch.
     */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                try {
                    val content = ctx.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (content != null) {
                        val name = IntentHelper.getFileName(ctx, uri) ?: "Imported Note"
                        databaseViewModel.upsertAsync(Note(0, name, content))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error importing file: $uri", e)
                }
            }
        }
    }

    /**
     * Writes [content] to the share cache as a `.md` file off the main thread,
     * then emits the resulting FileProvider URI on [shareUris]. Composables
     * collect this flow and dispatch the actual ACTION_SEND intent.
     */
    fun requestShare(title: String, content: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val cachePath = File(ctx.cacheDir, "shared_notes")
                cachePath.mkdirs()
                val file = File(cachePath, "$title.md")
                file.writeText(content)
                FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    file,
                )
            }
            _shareUris.emit(uri)
        }
    }

    companion object {
        private const val TAG = "NotesViewModel"
    }
}

/** Factory for constructing [NotesViewModel] with the shared [DatabaseViewModel]. */
class NotesViewModelFactory(
    private val application: Application,
    private val databaseViewModel: DatabaseViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotesViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return NotesViewModel(application, databaseViewModel) as T
    }
}
