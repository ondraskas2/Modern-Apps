package com.vayunmathur.photos.ui

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SecureFolderViewModel
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
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val allPhotos by galleryViewModel.photos.collectAsState()
    val photos by remember { derivedStateOf { allPhotos.filter { !it.isTrashed } } }
    val context = LocalContext.current
    var columnCount by LocalColumnCount.current

    val selectedIds by galleryViewModel.selectedIds.collectAsState()
    val isFeatureEnabled by galleryViewModel.isFeatureEnabled.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(Unit) {
        galleryViewModel.runSync()
        galleryViewModel.enqueueSync()
    }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            galleryViewModel.clearSelection()
            galleryViewModel.runSync()
        }
    }

    val moveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            galleryViewModel.clearSelection()
            galleryViewModel.runSync()
        }
    }

    val onMoveToSecureClick: () -> Unit = onMoveToSecureClick@{
        val activity = context as FragmentActivity
        val selectedPhotos = photos.filter { it.id in selectedIds }
        secureFolderViewModel.unlock(
            activity,
            onSuccess = { _, _ ->
                secureFolderViewModel.moveToSecure(
                    photos = selectedPhotos,
                    sourcePhotoDao = galleryViewModel.photoDao,
                ) { urisToDelete ->
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, urisToDelete)
                    moveLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                }
            },
            onFailure = {},
        )
    }

    val onDeleteClick: () -> Unit = {
        val uris = photos.filter { it.id in selectedIds }.map { it.uri.toUri() }
        val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
        trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    val resources = LocalResources.current
    val photosGroupedByMonth by remember {
        derivedStateOf {
            photos.groupBy {
                val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
                LocalDate(date.year, date.month, 1)
            }.toSortedMap(Comparator<LocalDate>(LocalDate::compareTo).reversed()).mapKeys {
                resources.getString(R.string.month_year_format, MonthNames.ENGLISH_ABBREVIATED.names[it.key.month.ordinal], it.key.year)
            }.mapValues { pair -> pair.value.sortedByDescending { it.date } }
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.items_selected, selectedIds.size)) },
                        navigationIcon = {
                            IconButton(onClick = { galleryViewModel.clearSelection() }) {
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
                                IconButton(onClick = { galleryViewModel.setFeatureEnabled(false) }) {
                                    Icon(painterResource(LibraryR.drawable.settings_24px), contentDescription = stringResource(R.string.disable_ocr))
                                }
                            }
                        }
                    )
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
                            onToggleSelection = { galleryViewModel.toggleSelection(photo.id) },
                            onClick = {
                                if (isSelectionMode) {
                                    galleryViewModel.toggleSelection(photo.id)
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
