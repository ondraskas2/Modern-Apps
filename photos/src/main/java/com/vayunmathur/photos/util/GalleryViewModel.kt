package com.vayunmathur.photos.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.photos.data.FaceDao
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.PhotoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import androidx.work.WorkManager

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
    val faceDao: FaceDao,
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

    /** True while a media sync is enqueued or running (drives pull-to-refresh). */
    val isRefreshing: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val ocrCount: StateFlow<Int> = photoDao.getOCRCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrTargetCount: StateFlow<Int> = photoDao.getOCRTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isFeatureEnabled: StateFlow<Boolean> = dataStore.booleanFlow("image_understanding_enabled")
        .onStart { emit(dataStore.getBoolean("image_understanding_enabled", false)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            false,
        )

    /** True while the face-grouping worker is actively indexing. */
    val faceIndexing: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(FaceWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Photos already scanned for faces (progress numerator). */
    val faceScannedCount: StateFlow<Int> = photoDao.getFaceScannedCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Photos that need face scanning in total (progress denominator). */
    val faceTargetCount: StateFlow<Int> = photoDao.getFaceTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Photos grouped by unnamed person-cluster, for the People view. */
    val people: StateFlow<List<PersonCluster>> =
        combine(photoDao.getAllFlow(), faceDao.personsFlow(), faceDao.allFacesFlow()) { allPhotos, persons, faces ->
            val byId = allPhotos.filter { !it.isTrashed }.associateBy { it.id }
            val facesByCluster = faces.groupBy { it.clusterId }
            persons.mapNotNull { person ->
                val personPhotos = facesByCluster[person.id].orEmpty()
                    .mapNotNull { byId[it.photoId] }
                    .distinctBy { it.id }
                    .sortedByDescending { it.date }
                if (personPhotos.isEmpty()) return@mapNotNull null
                val cover = byId[person.repPhotoId] ?: personPhotos.first()
                PersonCluster(
                    id = person.id,
                    coverPhoto = cover,
                    faceLeft = person.repLeft,
                    faceTop = person.repTop,
                    faceRight = person.repRight,
                    faceBottom = person.repBottom,
                    photos = personPhotos,
                )
            }.sortedByDescending { it.photos.size }
        }
            // Per-photo writes during indexing re-emit the source flows constantly;
            // drop emissions where the projected cluster list is unchanged (value
            // equality of the PersonCluster data class) so the grid doesn't churn.
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of detected faces (i.e. people) per photo, for the photo detail overlay. */
    val faceCountByPhoto: StateFlow<Map<Long, Int>> =
        faceDao.allFacesFlow().map { faces ->
            faces.groupBy { it.photoId }.mapValues { (_, list) -> list.size }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    /**
     * Index the single media [uri] into the gallery DB (so a freshly-captured
     * item handed to us via ACTION_VIEW exists before we open it), then return
     * its row id on the main thread. Also kicks a full background sync so the
     * rest of the library is fresh for swiping. Returns null if the item
     * couldn't be resolved.
     */
    fun resolveAndIndex(uri: Uri, onResolved: (Long?) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                val db = getApplication<Application>().buildDatabase<PhotoDatabase>()
                runCatching { syncPhotos(getApplication(), db, listOf(uri)) }
                photoDao.getByUri(uri.toString()).firstOrNull()?.id
            }
            runSync()
            onResolved(id)
        }
    }

    fun enqueueSync() {
        SyncWorker.enqueue(getApplication())
    }

    companion object {
        private const val TAG = "GalleryViewModel"
    }
}

@Suppress("FunctionName")
fun GalleryViewModelFactory(
    application: Application,
    photoDao: PhotoDao,
    faceDao: FaceDao,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { GalleryViewModel(application, photoDao, faceDao) }
}

/** An unnamed person-cluster and the library photos they appear in. */
data class PersonCluster(
    val id: Long,
    val coverPhoto: Photo,
    val faceLeft: Float,
    val faceTop: Float,
    val faceRight: Float,
    val faceBottom: Float,
    val photos: List<Photo>,
)
