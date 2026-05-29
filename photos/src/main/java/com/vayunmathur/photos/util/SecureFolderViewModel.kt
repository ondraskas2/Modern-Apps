package com.vayunmathur.photos.util

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.migration.Migration
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.VaultDatabase
import com.vayunmathur.photos.data.VaultPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Secure Folder (encrypted vault) feature.
 *
 * Owns:
 *  - vault biometric unlock + lazy DatabaseViewModel creation
 *  - decrypted-thumbnail bitmap cache (LRU, bounded)
 *  - encrypt/move and decrypt/restore operations off the main thread
 *
 * Bitmaps are recycled in [onCleared] to release native memory promptly.
 */
class SecureFolderViewModel(application: Application) : AndroidViewModel(application) {

    private val _vaultViewModel = MutableStateFlow<DatabaseViewModel?>(null)
    val vaultViewModel: StateFlow<DatabaseViewModel?> = _vaultViewModel.asStateFlow()

    private val _vaultPassword = MutableStateFlow<String?>(null)
    val vaultPassword: StateFlow<String?> = _vaultPassword.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val sfm: SecureFolderManager by lazy { SecureFolderManager(application) }

    // Bounded LRU cache for decrypted thumbnails. Cap 32 to prevent unbounded
    // bitmap retention while scrolling large vaults. Eldest entries are recycled
    // synchronously on eviction.
    private val thumbCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size > 32) {
                try { eldest.value.recycle() } catch (_: Exception) {}
                return true
            }
            return false
        }
    }

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    fun setVault(viewModel: DatabaseViewModel, password: String) {
        _vaultViewModel.value = viewModel
        _vaultPassword.value = password
    }

    fun unlock(
        activity: FragmentActivity,
        onSuccess: (DatabaseViewModel, String) -> Unit = { _, _ -> },
        onFailure: () -> Unit = {},
    ) {
        if (_vaultViewModel.value != null) {
            onSuccess(_vaultViewModel.value!!, _vaultPassword.value!!)
            return
        }
        unlockDatabaseWithBiometrics(
            activity,
            onSuccess = { password ->
                val db = activity.buildDatabase<VaultDatabase>(emptyList<Migration>(), password, "vault-db")
                val vvm = DatabaseViewModel(db, VaultPhoto::class to db.vaultPhotoDao())
                setVault(vvm, password)
                onSuccess(vvm, password)
            },
            onFailure = onFailure,
        )
    }

    /**
     * Decrypt a single thumbnail and publish into [thumbnails]. Composables read
     * `thumbnails.collectAsState()` and look up by path. Cached results return
     * immediately without re-decrypting.
     */
    fun requestThumbnail(thumbnailPath: String, password: String) {
        synchronized(thumbCache) {
            thumbCache[thumbnailPath]?.let {
                if (_thumbnails.value[thumbnailPath] !== it) {
                    _thumbnails.update { it + (thumbnailPath to thumbCache[thumbnailPath]!!) }
                }
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = sfm.decryptThumbnail(thumbnailPath, password) ?: return@launch
                synchronized(thumbCache) {
                    val existing = thumbCache[thumbnailPath]
                    if (existing != null) {
                        try { bmp.recycle() } catch (_: Exception) {}
                    } else {
                        thumbCache[thumbnailPath] = bmp
                    }
                }
                _thumbnails.update { current ->
                    current + (thumbnailPath to synchronized(thumbCache) { thumbCache[thumbnailPath]!! })
                }
            } catch (e: Exception) {
                Log.e(TAG, "decryptThumbnail failed for $thumbnailPath", e)
            }
        }
    }

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun addSelection(id: Long) {
        _selectedIds.update { it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * Restore a list of vault photos back to the MediaStore and delete the
     * matching VaultPhoto rows. Errors per photo are swallowed (mirrors
     * the existing UI behaviour).
     */
    fun restorePhotos(photos: List<VaultPhoto>, vault: DatabaseViewModel, password: String) {
        if (photos.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                photos.forEach { photo ->
                    val restored = sfm.decryptAndRestore(photo, password)
                    if (restored != null) {
                        vault.delete(photo)
                    }
                }
                clearSelection()
            } catch (e: Exception) {
                Log.e(TAG, "restorePhotos failed", e)
            }
        }
    }

    /**
     * Encrypt and move [photos] into the vault. Returns the original MediaStore
     * URIs through [onSuccess] so the caller can issue the MediaStore delete
     * request (the only step that must run on the activity).
     */
    fun moveToSecure(
        photos: List<Photo>,
        sourceViewModel: DatabaseViewModel,
        vault: DatabaseViewModel,
        password: String,
        onSuccess: (List<android.net.Uri>) -> Unit,
    ) {
        if (photos.isEmpty()) return
        viewModelScope.launch {
            val urisToDelete = withContext(Dispatchers.IO) {
                val collected = mutableListOf<android.net.Uri>()
                photos.forEach { photo ->
                    try {
                        val (path, thumbPath) = sfm.encryptAndMove(
                            android.net.Uri.parse(photo.uri),
                            photo.name,
                            password,
                        )
                        vault.upsert(
                            VaultPhoto(
                                name = photo.name,
                                path = path,
                                thumbnailPath = thumbPath,
                                date = photo.date,
                                width = photo.width,
                                height = photo.height,
                                dateModified = photo.dateModified,
                                videoDuration = photo.videoData?.duration,
                            )
                        )
                        collected.add(android.net.Uri.parse(photo.uri))
                        sourceViewModel.delete(photo)
                    } catch (e: Exception) {
                        Log.e(TAG, "encryptAndMove failed for ${photo.uri}", e)
                    }
                }
                collected
            }
            if (urisToDelete.isNotEmpty()) {
                onSuccess(urisToDelete)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(thumbCache) {
            thumbCache.values.forEach { bmp ->
                try { if (!bmp.isRecycled) bmp.recycle() } catch (_: Exception) {}
            }
            thumbCache.clear()
        }
        _thumbnails.value = emptyMap()
    }

    companion object {
        private const val TAG = "SecureFolderViewModel"
    }
}

class SecureFolderViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SecureFolderViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return SecureFolderViewModel(application) as T
    }
}
