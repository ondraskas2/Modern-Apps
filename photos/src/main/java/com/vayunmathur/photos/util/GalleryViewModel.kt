package com.vayunmathur.photos.util

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the photos gallery screen.
 *
 * Owns:
 *  - the observable list of [Photo]s (backed by the DAO Flow)
 *  - OCR search query and asynchronously fetched search results
 *  - multi-select state (set of photo ids)
 *  - OCR feature-enabled flag (DataStore) and OCR progress counters (Flow)
 *  - sync worker entry-point side effects
 */
@OptIn(FlowPreview::class)
class GalleryViewModel(
    application: Application,
    val photoDao: PhotoDao,
) : AndroidViewModel(application) {

    private val dataStore: DataStoreUtils = DataStoreUtils.getInstance(application)

    val photos: StateFlow<List<Photo>> = photoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Photo>>(emptyList())
    val searchResults: StateFlow<List<Photo>> = _searchResults.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val ocrCount: StateFlow<Int> = photoDao.getOCRCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrTargetCount: StateFlow<Int> = photoDao.getOCRTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isFeatureEnabled: StateFlow<Boolean> = dataStore.booleanFlow("image_understanding_enabled")
        .onStart { emit(dataStore.getBoolean("image_understanding_enabled", false)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            dataStore.getBoolean("image_understanding_enabled", false),
        )

    val isOpenAssistantInstalled: Boolean = try {
        application.packageManager.getPackageInfo("com.vayunmathur.openassistant", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    init {
        // Debounced search: re-query whenever the query string changes.
        viewModelScope.launch {
            _searchQuery
                .debounce(150)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                        return@collectLatest
                    }
                    val results = withContext(Dispatchers.IO) {
                        try {
                            photoDao.searchPhotos("$query*")
                        } catch (e: Exception) {
                            Log.e(TAG, "searchPhotos failed", e)
                            emptyList()
                        }
                    }
                    Log.d(TAG, "Search '$query*' returned ${results.size} photos")
                    _searchResults.value = results
                }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun setFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("image_understanding_enabled", enabled)
        }
    }

    fun deletePhoto(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            photoDao.delete(photo)
        }
    }

    fun runSync() {
        SyncWorker.runOnce(getApplication())
    }

    fun enqueueSync() {
        SyncWorker.enqueue(getApplication())
    }

    companion object {
        private const val TAG = "GalleryViewModel"
    }
}

class GalleryViewModelFactory(
    private val application: Application,
    private val photoDao: PhotoDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return GalleryViewModel(application, photoDao) as T
    }
}
