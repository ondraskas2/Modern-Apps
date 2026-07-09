package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.ComposePdfDocument
import com.vayunmathur.pdf.util.SafePdfPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * "Cut and glue": compose a new PDF by appending whole PDFs or images, then
 * drag pages into the desired order. Starts empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutGlueScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler { onBack() }
    val doc = remember { ComposePdfDocument.create() }
    DisposableEffect(doc) { onDispose { doc.close() } }

    // Stable per-page keys so drag-reorder animates; order mirrors native pages.
    val pageKeys = remember { mutableStateListOf<Long>() }
    var nextKey by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    fun refresh() {
        version++
    }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            } ?: return@launch
            val added = doc.appendPdf(bytes)
            repeat(added) { pageKeys.add(nextKey++.toLong()) }
            refresh()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { readAsJpegPage(context, uri) } ?: return@launch
            val ok = doc.appendImage(jpeg.bytes, jpeg.width, jpeg.height)
            if (ok > 0) pageKeys.add(nextKey++.toLong())
            refresh()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        if (outUri != null) scope.launch {
            val bytes = doc.save()
            if (bytes != null) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
            }
        }
    }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (from.index < pageKeys.size && to.index < pageKeys.size) {
            val k = pageKeys.removeAt(from.index)
            pageKeys.add(to.index, k)
            scope.launch { doc.movePage(from.index, to.index); refresh() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cut & Glue") },
                navigationIcon = { IconNavigation { onBack() } },
                actions = {
                    if (pageKeys.isNotEmpty()) {
                        IconButton({ saveLauncher.launch("composed.pdf") }) { IconSave() }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { IconAdd() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(painterResource(R.drawable.ic_tool_image), null) },
                        text = { Text("Append image") },
                        onClick = {
                            menuOpen = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(painterResource(R.drawable.ic_tool_rect), null) },
                        text = { Text("Append PDF") },
                        onClick = { menuOpen = false; pdfPicker.launch(arrayOf("application/pdf")) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (pageKeys.isEmpty()) {
                Text(
                    "Tap + to append a PDF or image",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                ) {
                    items(pageKeys, key = { it }) { key ->
                        val index = pageKeys.indexOf(key)
                        ReorderableItem(reorderState, key = key) { _ ->
                            ComposePageThumb(
                                doc = doc,
                                index = index,
                                version = version,
                                onDelete = {
                                    if (index in pageKeys.indices) {
                                        pageKeys.removeAt(index)
                                        scope.launch { doc.removePage(index); refresh() }
                                    }
                                },
                                dragHandle = Modifier.longPressDraggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposePageThumb(
    doc: ComposePdfDocument,
    index: Int,
    version: Int,
    onDelete: () -> Unit,
    dragHandle: Modifier,
) {
    val page by produceState<SafePdfPage?>(null, index, version) {
        value = if (index >= 0) doc.renderPage(index) else null
    }
    val current = page
    val ratio = if (current != null && current.height > 0f) current.width / current.height else 0.75f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(dragHandle),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
        ) {
            if (current == null || current.width <= 0f) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Canvas(Modifier.fillMaxSize()) { drawSafePage(current) }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd),
        ) { IconDelete() }
    }
}

private class JpegPage(val bytes: ByteArray, val width: Int, val height: Int)

/** Decode [uri] and re-encode as JPEG for an image page; null on failure. */
private fun readAsJpegPage(context: android.content.Context, uri: Uri): JpegPage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    JpegPage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
