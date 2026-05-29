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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconUnarchive
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SyncWorker
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashPage(backStack: NavBackStack<Route>, galleryViewModel: GalleryViewModel) {
    val allPhotos by galleryViewModel.photos.collectAsState()
    val trashedPhotos by remember { derivedStateOf { allPhotos.filter { it.isTrashed } } }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    var columnCount by LocalColumnCount.current

    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedIds.clear()
            SyncWorker.runOnce(context)
        }
    }

    val photosGroupedByMonth by remember {
        derivedStateOf {
            trashedPhotos.groupBy {
                val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
                LocalDate(date.year, date.month, 1)
            }.toSortedMap(Comparator<LocalDate>(LocalDate::compareTo).reversed()).mapKeys {
                resources.getString(com.vayunmathur.photos.R.string.month_year_format, MonthNames.ENGLISH_ABBREVIATED.names[it.key.month.ordinal], it.key.year)
            }.mapValues { pair -> pair.value.sortedByDescending { it.date } }
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(com.vayunmathur.photos.R.string.items_selected, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val uris = trashedPhotos.filter { it.id in selectedIds }.map { it.uri.toUri() }
                            val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, false)
                            trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        }) {
                            IconUnarchive() // Restore icon
                        }
                        IconButton(onClick = {
                            val uris = trashedPhotos.filter { it.id in selectedIds }.map { it.uri.toUri() }
                            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                            trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        }) {
                            IconDelete() // Permanent delete icon
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(com.vayunmathur.photos.R.string.label_trash)) },
                    actions = {
                        if (trashedPhotos.isNotEmpty()) {
                            IconButton(onClick = {
                                val uris = trashedPhotos.map { it.uri.toUri() }
                                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                                trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                            }) {
                                IconDelete()
                            }
                        }
                    }
                )
            }
        },
        bottomBar = { if (!isSelectionMode) NavigationBar(Route.Trash, backStack) }
    ) { paddingValues ->
        if (trashedPhotos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("Trash is empty", color = Color.Gray)
            }
        } else {
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
                                        selectedIds.add(photo.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
