package com.vayunmathur.photos.ui

import android.app.Activity
import android.util.Log
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import android.content.pm.PackageManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.unlockDatabaseWithBiometrics
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.onStart
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.VaultDatabase
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SecureFolderManager
import com.vayunmathur.photos.util.SyncWorker
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant
import com.vayunmathur.library.R as LibraryR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    vaultViewModel: DatabaseViewModel? = null,
    vaultPassword: String? = null,
    onVaultUnlocked: (DatabaseViewModel, String) -> Unit = { _, _ -> }
) {
    val allPhotos by viewModel.data<Photo>().collectAsState()
    val photos by remember { derivedStateOf { allPhotos.filter { !it.isTrashed } } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var columnCount by LocalColumnCount.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchResults = remember(searchQuery, allPhotos) {
        mutableStateListOf<Photo>()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            val photoDao = viewModel.getDao<Photo>() as PhotoDao
            val results = photoDao.searchPhotos("$searchQuery*")
            Log.d("GalleryPage", "Search for '$searchQuery*' returned ${results.size} photos")
            searchResults.clear()
            searchResults.addAll(results)
        }
    }

    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    val photoDao = remember { viewModel.getDao<Photo>() as PhotoDao }
    val ocrCount by photoDao.getOCRCountFlow().collectAsState(0)
    val targetCount by photoDao.getOCRTargetCountFlow().collectAsState(0)
    val ocrProgress by remember { derivedStateOf { if (targetCount > 0) ocrCount.toFloat() / targetCount else 1f } }
    
    val dataStore = remember { DataStoreUtils.getInstance(context) }
    val featureEnabledFlow = remember { dataStore.booleanFlow("image_understanding_enabled").onStart { emit(dataStore.getBoolean("image_understanding_enabled", false)) } }
    val isFeatureEnabled by featureEnabledFlow.collectAsState(initial = dataStore.getBoolean("image_understanding_enabled", false))
    val isOpenAssistantInstalled = remember(context) {
        try {
            context.packageManager.getPackageInfo("com.vayunmathur.openassistant", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedIds.clear()
            SyncWorker.runOnce(context)
        }
    }

    val moveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedIds.clear()
            SyncWorker.runOnce(context)
        }
    }

    val onMoveToSecureClick = {
        val activity = context as FragmentActivity
        fun performMove(vvm: DatabaseViewModel, pass: String) {
            scope.launch {
                val sfm = SecureFolderManager(context)
                val urisToDelete = mutableListOf<Uri>()
                val selectedPhotos = photos.filter { it.id in selectedIds }
                selectedPhotos.forEach { photo ->
                    try {
                        val (path, thumbPath) = sfm.encryptAndMove(
                            photo.uri.toUri(),
                            photo.name,
                            pass,
                            photo.videoData != null
                        )
                        vvm.upsert(VaultPhoto(
                            name = photo.name,
                            path = path,
                            thumbnailPath = thumbPath,
                            date = photo.date,
                            width = photo.width,
                            height = photo.height,
                            dateModified = photo.dateModified,
                            videoDuration = photo.videoData?.duration
                        ))
                        urisToDelete.add(photo.uri.toUri())
                        viewModel.delete(photo)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (urisToDelete.isNotEmpty()) {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, urisToDelete)
                    moveLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                }
            }
        }

        if (vaultViewModel == null) {
            unlockDatabaseWithBiometrics(
                activity,
                onSuccess = { password ->
                    val db = activity.buildDatabase<VaultDatabase>(emptyList<Migration>(), password, "vault-db")
                    val vvm = DatabaseViewModel(db, VaultPhoto::class to db.vaultPhotoDao())
                    onVaultUnlocked(vvm, password)
                    performMove(vvm, password)
                },
                onFailure = {}
            )
        } else {
            performMove(vaultViewModel, vaultPassword!!)
        }
    }

    val onDeleteClick = {
        val uris = photos.filter { it.id in selectedIds }.map { it.uri.toUri() }
        val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
        trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    val photosGroupedByMonth by remember {
        derivedStateOf {
            photos.groupBy {
                val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
                LocalDate(date.year, date.month, 1)
            }.toSortedMap(Comparator<LocalDate>(LocalDate::compareTo).reversed()).mapKeys {
                context.getString(R.string.month_year_format, MonthNames.ENGLISH_ABBREVIATED.names[it.key.month.ordinal], it.key.year)
            }.mapValues { pair -> pair.value.sortedByDescending { it.date } }
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode && !isSearchActive) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.items_selected, selectedIds.size)) },
                        navigationIcon = {
                            IconButton(onClick = { selectedIds.clear() }) {
                                IconClose()
                            }
                        },
                        actions = {
                            IconButton(onClick = onMoveToSecureClick) {
                                Icon(painterResource(R.drawable.lock_24px), contentDescription = stringResource(R.string.action_move_to_secure))
                            }
                            IconButton(onClick = onDeleteClick) {
                                IconDelete()
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        actions = {
                            if (isFeatureEnabled) {
                                IconButton(onClick = {
                                    scope.launch {
                                        dataStore.setBoolean("image_understanding_enabled", false)
                                    }
                                }) {
                                    Icon(painterResource(LibraryR.drawable.settings_24px), contentDescription = stringResource(R.string.disable_ocr))
                                }
                            }
                        }
                    )
//                    SearchBar(
//                        inputField = {
//                            SearchBarDefaults.InputField(
//                                query = if (isSelectionMode && isSearchActive) stringResource(R.string.items_selected, selectedIds.size) else searchQuery,
//                                onQueryChange = { if (!isSelectionMode) searchQuery = it },
//                                onSearch = { isSearchActive = false },
//                                expanded = isSearchActive,
//                                onExpandedChange = { if (!isSelectionMode) isSearchActive = it },
//                                placeholder = { Text(stringResource(R.string.search_photos)) },
//                                leadingIcon = {
//                                    if (isSelectionMode && isSearchActive) {
//                                        IconButton(onClick = { selectedIds.clear() }) {
//                                            IconClose()
//                                        }
//                                    } else {
//                                        IconSearch()
//                                    }
//                                },
//                                trailingIcon = {
//                                    if (isSelectionMode && isSearchActive) {
//                                        Row(verticalAlignment = Alignment.CenterVertically) {
//                                            IconButton(onClick = onMoveToSecureClick) {
//                                                Icon(painterResource(R.drawable.lock_24px), contentDescription = stringResource(R.string.action_move_to_secure))
//                                            }
//                                            IconButton(onClick = onDeleteClick) {
//                                                IconDelete()
//                                            }
//                                        }
//                                    } else if (searchQuery.isNotEmpty()) {
//                                        IconButton(onClick = { searchQuery = "" }) {
//                                            IconClose()
//                                        }
//                                    } else if (ocrProgress < 1f && isFeatureEnabled) {
//                                        Box(Modifier.padding(8.dp)) {
//                                            CircularProgressIndicator(
//                                                progress = { ocrProgress },
//                                                modifier = Modifier.size(24.dp),
//                                                strokeWidth = 2.dp,
//                                            )
//                                        }
//                                    }
//                                }
//                            )
//                        },
//                        expanded = isSearchActive,
//                        onExpandedChange = { if (!isSelectionMode) isSearchActive = it },
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .padding(horizontal = if (isSearchActive) 0.dp else 16.dp)
//                            .padding(top = if (isSearchActive) 0.dp else 8.dp),
//                        content = {
//                            Column(
//                                modifier = Modifier
//                                    .fillMaxSize()
//                                    .pointerInput(Unit) {
//                                        awaitEachGesture {
//                                            while (true) {
//                                                val event = awaitPointerEvent(PointerEventPass.Initial)
//                                                if (event.changes.size > 1) {
//                                                    val zoom = event.calculateZoom()
//                                                    if (zoom != 1f) {
//                                                        columnCount = (columnCount / zoom).coerceIn(2f, 8f)
//                                                        event.changes.forEach { it.consume() }
//                                                    }
//                                                }
//                                                if (event.changes.all { it.changedToUp() }) break
//                                            }
//                                        }
//                                    }
//                            ) {
//                                if (!isFeatureEnabled) {
//                                    Column(
//                                        Modifier
//                                            .fillMaxSize()
//                                            .padding(32.dp),
//                                        horizontalAlignment = Alignment.CenterHorizontally,
//                                        verticalArrangement = Arrangement.Center
//                                    ) {
//                                        Icon(
//                                            painterResource(R.drawable.lock_24px),
//                                            contentDescription = null,
//                                            modifier = Modifier.size(48.dp),
//                                            tint = MaterialTheme.colorScheme.primary
//                                        )
//                                        Spacer(Modifier.height(16.dp))
//                                        Text(
//                                            stringResource(R.string.image_understanding_title),
//                                            style = MaterialTheme.typography.headlineSmall
//                                        )
//                                        Spacer(Modifier.height(8.dp))
//                                        Text(
//                                            stringResource(R.string.image_understanding_description),
//                                            style = MaterialTheme.typography.bodyMedium
//                                        )
//                                        if (!isOpenAssistantInstalled) {
//                                            Spacer(Modifier.height(16.dp))
//                                            Text(
//                                                stringResource(R.string.openassistant_not_found),
//                                                color = MaterialTheme.colorScheme.error,
//                                                style = MaterialTheme.typography.bodySmall
//                                            )
//                                        }
//                                        Spacer(Modifier.height(24.dp))
//                                        TextButton(
//                                            onClick = {
//                                                scope.launch {
//                                                    dataStore.setBoolean("image_understanding_enabled", true)
//                                                    SyncWorker.runOnce(context)
//                                                }
//                                            },
//                                            enabled = isOpenAssistantInstalled
//                                        ) {
//                                            Text(stringResource(R.string.enable_feature))
//                                        }
//                                    }
//                                } else if (searchQuery.isBlank()) {
//                                    Column(
//                                        Modifier
//                                            .fillMaxWidth()
//                                            .padding(32.dp),
//                                        horizontalAlignment = Alignment.CenterHorizontally
//                                    ) {
//                                        Text(
//                                            stringResource(R.string.indexing_photos),
//                                            style = MaterialTheme.typography.titleMedium
//                                        )
//                                        Spacer(Modifier.height(8.dp))
//                                        Text(
//                                            stringResource(R.string.indexing_progress_details, ocrCount, targetCount),
//                                            style = MaterialTheme.typography.bodySmall
//                                        )
//                                        Spacer(Modifier.height(16.dp))
//                                        LinearProgressIndicator(
//                                            progress = { ocrProgress },
//                                            modifier = Modifier.fillMaxWidth()
//                                        )
//                                        Spacer(Modifier.height(8.dp))
//                                        Text(
//                                            "${(ocrProgress * 100).roundToInt()}%",
//                                            style = MaterialTheme.typography.bodySmall,
//                                            modifier = Modifier.align(Alignment.End)
//                                        )
//                                    }
//                                } else {
//                                    LazyVerticalGrid(
//                                        GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
//                                        Modifier.fillMaxSize(),
//                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
//                                        verticalArrangement = Arrangement.spacedBy(4.dp)
//                                    ) {
//                                        items(searchResults, { it.id }, contentType = { "photo_thumbnail" }) { photo ->
//                                            val isSelected = photo.id in selectedIds
//                                            ImageLoader.SelectablePhotoItem(
//                                                photo = photo,
//                                                isSelected = isSelected,
//                                                isSelectionMode = isSelectionMode,
//                                                onToggleSelection = {
//                                                    if (isSelected) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
//                                                },
//                                                onClick = {
//                                                    if (isSelectionMode) {
//                                                        if (isSelected) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
//                                                    } else {
//                                                        backStack.add(Route.PhotoPage(photo.id, null))
//                                                    }
//                                                }
//                                            )
//                                        }
//                                    }
//                                }
//                            }
//                        }
//                    )
                }
            }
        },
        bottomBar = { if (!isSelectionMode) NavigationBar(Route.Gallery, backStack) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size > 1) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    columnCount = (columnCount / zoom).coerceIn(2f, 8f)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            if (event.changes.all { it.changedToUp() }) break
                        }
                    }
                }
        ) {
            LazyVerticalGrid(
                GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                Modifier.padding(paddingValues),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                photosGroupedByMonth.forEach { (month, photosInMonth) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            month,
                            Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    items(photosInMonth, { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                        val isSelected = photo.id in selectedIds
                        ImageLoader.SelectablePhotoItem(
                            photo = photo,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onToggleSelection = {
                                if (isSelected) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
                            },
                            onClick = {
                                if (isSelectionMode) {
                                    if (isSelected) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
                                } else {
                                    backStack.add(Route.PhotoPage(photo.id, null))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
