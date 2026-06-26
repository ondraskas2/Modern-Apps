package com.vayunmathur.files.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.R
import com.vayunmathur.files.deleteRecursively
import com.vayunmathur.files.fs
import com.vayunmathur.files.isDirectory
import com.vayunmathur.files.listFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.openZip
import okio.source

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs =
        application.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)

    // ---- Permission state ----

    private val _isFilesGranted = MutableStateFlow(Environment.isExternalStorageManager())
    val isFilesGranted: StateFlow<Boolean> = _isFilesGranted.asStateFlow()

    /** Re-checks the MANAGE_EXTERNAL_STORAGE permission (call from MainActivity onResume). */
    fun refreshPermissions() {
        val granted = Environment.isExternalStorageManager()
        if (_isFilesGranted.value != granted) {
            _isFilesGranted.value = granted
            if (granted) loadDirectory()
        }
    }

    private val _hasPromptedNotifications =
        MutableStateFlow(prefs.getBoolean("has_prompted_notifications", false))
    val hasPromptedNotifications: StateFlow<Boolean> = _hasPromptedNotifications.asStateFlow()

    fun setNotificationsPrompted() {
        prefs.edit { putBoolean("has_prompted_notifications", true) }
        _hasPromptedNotifications.value = true
    }

    // ---- Navigation state ----

    /** Stable storage-root path used for "home" / back-handler base. */
    val rootDirectory: Path = Environment.getExternalStorageDirectory().toOkioPath()

    private val _currentFileSystem = MutableStateFlow<FileSystem>(fs)
    val currentFileSystem: StateFlow<FileSystem> = _currentFileSystem.asStateFlow()

    private val _currentDirectory = MutableStateFlow(rootDirectory)
    val currentDirectory: StateFlow<Path> = _currentDirectory.asStateFlow()

    /** The on-disk zip file when browsing inside an opened archive; `null` otherwise. */
    private val _zipPath = MutableStateFlow<Path?>(null)
    val zipPath: StateFlow<Path?> = _zipPath.asStateFlow()

    // ---- Directory listing ----

    /** (directories, files) for [currentDirectory], partitioned & sorted on Dispatchers.IO. */
    private val _entries =
        MutableStateFlow<Pair<List<Path>, List<Path>>>(emptyList<Path>() to emptyList())
    val entries: StateFlow<Pair<List<Path>, List<Path>>> = _entries.asStateFlow()

    // ---- Selection ----

    private val _selectedPaths = MutableStateFlow<Set<Path>>(emptySet())
    val selectedPaths: StateFlow<Set<Path>> = _selectedPaths.asStateFlow()

    fun clearSelection() {
        if (_selectedPaths.value.isNotEmpty()) _selectedPaths.value = emptySet()
    }

    fun addToSelection(path: Path) {
        _selectedPaths.value = _selectedPaths.value + path
    }

    fun toggleSelection(path: Path) {
        val current = _selectedPaths.value
        _selectedPaths.value = if (path in current) current - path else current + path
    }

    // ---- Incoming share-intent URIs ----

    private val _incomingUris = MutableStateFlow<List<Uri>?>(null)
    val incomingUris: StateFlow<List<Uri>?> = _incomingUris.asStateFlow()

    fun setIncomingUris(uris: List<Uri>) {
        _incomingUris.value = uris
    }

    fun clearIncomingUris() {
        _incomingUris.value = null
    }

    // ---- One-shot UI events ----

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    /** Emits ACTION_VIEW intents for files the user taps; MainActivity launches them. */
    val intents: SharedFlow<Intent> = _intents.asSharedFlow()

    // ---- FileObserver lifecycle ----

    private var observerJob: Job? = null

    init {
        loadDirectory()
        restartObserver()
    }

    /** Re-list the current directory off the main thread. */
    fun loadDirectory() {
        val dir = _currentDirectory.value
        val fileSystem = _currentFileSystem.value
        viewModelScope.launch(Dispatchers.IO) {
            _entries.value = dir.listFiles(fileSystem).partition { it.isDirectory(fileSystem) }
        }
    }

    private fun restartObserver() {
        observerJob?.cancel()
        if (_currentFileSystem.value != fs) {
            observerJob = null
            return
        }
        val dir = _currentDirectory.value
        observerJob = viewModelScope.launch {
            val observer = object : FileObserver(
                dir.toFile(), CREATE or DELETE or MOVED_FROM or MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    loadDirectory()
                }
            }
            observer.startWatching()
            try {
                awaitCancellation()
            } finally {
                observer.stopWatching()
            }
        }
    }

    /** Navigate to [path] on [fileSystem]; clears selection and the open zip when leaving zip mode. */
    fun navigateTo(path: Path, fileSystem: FileSystem) {
        _currentFileSystem.value = fileSystem
        _currentDirectory.value = path
        if (fileSystem == fs) _zipPath.value = null
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    /**
     * Back-handler logic mirroring the original `BackHandler` body. Returns `true` if it consumed
     * the event.
     */
    fun handleBack(): Boolean {
        if (_selectedPaths.value.isNotEmpty()) {
            clearSelection()
            return true
        }
        val z = _zipPath.value
        when {
            z != null -> {
                val cur = _currentDirectory.value
                if (cur.toString() == "/" || cur.name.isEmpty()) {
                    navigateTo(z.parent ?: rootDirectory, fs)
                } else {
                    _currentDirectory.value = cur.parent ?: "/".toPath()
                    loadDirectory()
                    restartObserver()
                }
            }
            _currentDirectory.value != rootDirectory -> {
                _currentDirectory.value = _currentDirectory.value.parent ?: _currentDirectory.value
                loadDirectory()
                restartObserver()
            }
            else -> return false
        }
        return true
    }

    // ---- File ops ----

    fun rename(path: Path, newName: String) {
        val fileSystem = _currentFileSystem.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fileSystem.atomicMove(path, path.parent!!.resolve(newName))
                clearSelection()
                loadDirectory()
            } catch (e: Exception) {
                emitMoveFailed(e)
            }
        }
    }

    fun deleteSelection() {
        val fileSystem = _currentFileSystem.value
        val selection = _selectedPaths.value
        viewModelScope.launch(Dispatchers.IO) {
            selection.forEach { it.deleteRecursively(fileSystem) }
            clearSelection()
            loadDirectory()
        }
    }

    fun moveInto(sources: List<Path>, target: Path) {
        if (!target.isDirectory(_currentFileSystem.value)) return
        moveFiles(sources, target, _currentFileSystem.value) { source ->
            source != target && !target.toString().startsWith(source.toString())
        }
    }

    fun moveToBreadcrumb(sources: List<Path>, target: Path) {
        moveFiles(sources, target, fs) { source ->
            source.parent != target && source != target
        }
    }

    private fun moveFiles(sources: List<Path>, target: Path, fileSystem: FileSystem, canMove: (Path) -> Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var movedAny = false
            var lastError: Exception? = null
            sources.forEach { source ->
                if (canMove(source)) {
                    try {
                        fileSystem.atomicMove(source, target.resolve(source.name))
                        movedAny = true
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
            if (movedAny) {
                clearSelection()
                loadDirectory()
            }
            lastError?.let { emitMoveFailed(it) }
        }
    }

    /** Try to open [path] as a zip and descend into it. */
    fun openZipFile(path: Path) {
        val fileSystem = _currentFileSystem.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val zipFs = fileSystem.openZip(path)
                if (_zipPath.value == null) _zipPath.value = path
                _currentFileSystem.value = zipFs
                _currentDirectory.value = "/".toPath()
                clearSelection()
                loadDirectory()
                restartObserver()
            } catch (e: Exception) {
                emit(
                    getApplication<Application>()
                        .getString(R.string.could_not_open_zip, e.localizedMessage),
                )
            }
        }
    }

    /** Enqueue a [ZipWorker] archiving the current selection into [archiveName] in the current dir. */
    fun archive(archiveName: String) {
        val ctx = getApplication<Application>()
        val sources = _selectedPaths.value
        val destPath = _currentDirectory.value.resolve(
            if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip",
        )
        val zipWork = OneTimeWorkRequestBuilder<ZipWorker>().setInputData(
            workDataOf(
                "source_paths" to sources.map { it.toString() }.toTypedArray(),
                "dest_path" to destPath.toString(),
            ),
        ).build()
        WorkManager.getInstance(ctx).enqueue(zipWork)
        clearSelection()
        emit(ctx.getString(R.string.archiving_started))
    }

    /** Enqueue an [UnzipWorker] for the single selected zip extracting into [destPath]. */
    fun unzip(zipPath: Path, destPath: Path) {
        val ctx = getApplication<Application>()
        val unzipWork = OneTimeWorkRequestBuilder<UnzipWorker>().setInputData(
            workDataOf(
                "zip_path" to zipPath.toString(),
                "dest_path" to destPath.toString(),
            ),
        ).build()
        WorkManager.getInstance(ctx).enqueue(unzipWork)
        clearSelection()
        emit(ctx.getString(R.string.unzipping_started_to, destPath.name))
    }

    /** Save any URIs queued in [incomingUris] into [currentDirectory], then clear them. */
    fun saveIncomingUris() {
        val ctx = getApplication<Application>()
        val uris = _incomingUris.value ?: return
        val target = _currentDirectory.value
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri -> saveUriToPath(ctx, uri, target) }
            clearIncomingUris()
            loadDirectory()
            _snackbarMessages.emit(ctx.getString(R.string.files_saved))
        }
    }

    /**
     * Open [path] with an external app via ACTION_VIEW. The intent is emitted on [intents]
     * and launched by MainActivity; in zip-browse mode emits a snackbar instead.
     */
    fun openFile(path: Path) {
        val ctx = getApplication<Application>()
        if (_currentFileSystem.value != fs) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val file = path.toFile()
        val extension = file.extension
        val mimeType =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file,
            )
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        viewModelScope.launch { _intents.emit(intent) }
    }

    fun showMessage(message: String) {
        emit(message)
    }

    private fun emit(message: String) {
        viewModelScope.launch { _snackbarMessages.emit(message) }
    }

    private fun emitMoveFailed(e: Exception) {
        emit(
            getApplication<Application>()
                .getString(R.string.move_failed, e.localizedMessage),
        )
    }

    private fun saveUriToPath(context: Context, uri: Uri, targetDir: Path) {
        val name = getFileName(context, uri) ?: "shared_file_${System.currentTimeMillis()}"
        val targetPath = targetDir.resolve(name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            fs.write(targetPath) { writeAll(input.source()) }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) return cursor.getString(nameIndex)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }
}
