package com.vayunmathur.pdf.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.pdf.util.SafeOutlineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafeAnnotation
import com.vayunmathur.pdf.util.SafeFormField
import com.vayunmathur.pdf.util.SafePdfDocument
import com.vayunmathur.pdf.util.SafePdfPage
import java.io.ByteArrayOutputStream

private sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Loaded(val document: SafePdfDocument) : LoadState
}

/** Editing tools. */
private enum class EditTool { SELECT, TEXT, HIGHLIGHT, DRAW, RECT, IMAGE }

/**
 * An in-progress inline text edit. [origin] is the top of the text box in page
 * space; [annotId] is set when editing an existing FreeText, null for a new one.
 */
private data class TextSession(
    val page: Int,
    val origin: Offset,
    val size: Float,
    val color: Int,
    val annotId: Long?,
    val value: TextFieldValue,
)

/**
 * Read-only + overlay-editing PDF viewer that never touches the system PDF
 * stack: pages are parsed in Rust ([SafePdfDocument]) and drawn from plain
 * primitives on a Compose [Canvas]. Editing (annotations, form filling) is
 * written back through lopdf and saved via SAF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafePdfViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val loadState by produceState<LoadState>(LoadState.Loading, uri) {
        value = SafePdfDocument.open(context, uri)
            ?.let { LoadState.Loaded(it) }
            ?: LoadState.Error
    }
    val document = (loadState as? LoadState.Loaded)?.document
    DisposableEffect(document) { onDispose { document?.close() } }

    // Prebuild the search index in the background so the first query is instant.
    LaunchedEffect(document) { document?.prewarmSearch() }

    var editMode by remember { mutableStateOf(false) }
    var showSaveMenu by remember { mutableStateOf(false) }
    // Set once any edit is made; keeps the Save control visible thereafter.
    var dirty by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(EditTool.SELECT) }
    var color by remember { mutableStateOf(Color.Red) }
    // Bumped after any edit to force affected pages to re-render.
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    // Re-render affected pages and record that an edit was made.
    val markEdited = { version++; dirty = true }

    // Search state.
    val listState = rememberLazyListState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Matches as (pageIndex, page-space rect).
    var matches by remember { mutableStateOf<List<Pair<Int, Rect>>>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, document, searching) {
        val doc = document
        if (doc == null || !searching || query.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        matches = doc.search(query).map { m ->
            m.page to Rect(m.x0, m.y0, m.x1, m.y1)
        }
        matchIndex = 0
    }

    LaunchedEffect(matchIndex, matches) {
        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it.first) }
    }

    // Outline (bookmarks) + navigation drawer.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val outline by produceState(emptyList<SafeOutlineItem>(), document) {
        value = document?.outline() ?: emptyList()
    }

    // Pinch-to-zoom + pan (two-finger); single-finger still scrolls.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
        pan = if (zoom > 1f) pan + panChange else Offset.Zero
    }

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) runCatching { searchFocus.requestFocus() }
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            searching -> { searching = false; query = "" }
            else -> onBack()
        }
    }

    // Inline text-editing session (draws a live text field on the page).
    var textSession by remember { mutableStateOf<TextSession?>(null) }
    // Image-stamp target awaiting a picked image.
    var imageTarget by remember { mutableStateOf<Pair<Int, Offset>?>(null) }

    // Persist a finished text session as a FreeText annotation.
    val commitText: (TextSession?) -> Unit = commit@{ s ->
        if (s == null) return@commit
        val doc = document ?: return@commit
        val txt = s.value.text.trim()
        scope.launch {
            when {
                s.annotId != null && txt.isEmpty() -> doc.deleteAnnotation(s.page, s.annotId)
                s.annotId != null -> doc.editText(s.page, s.annotId, txt)
                txt.isNotEmpty() -> doc.addText(
                    s.page, s.origin.x, s.origin.y - s.size * 1.3f, s.origin.x + 220f, s.origin.y,
                    s.color, s.size, txt,
                )
                else -> return@launch
            }
            markEdited()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        val doc = document
        if (outUri != null && doc != null) {
            scope.launch {
                val bytes = doc.save()
                if (bytes != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) }
                    }
                }
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imgUri ->
        val doc = document
        val target = imageTarget
        imageTarget = null
        if (imgUri != null && doc != null && target != null) {
            scope.launch {
                val jpeg = readAsJpeg(context, imgUri) ?: return@launch
                val (index, pt) = target
                // Default 150pt-wide stamp preserving aspect ratio.
                val w = 150f
                val h = w * jpeg.height / jpeg.width.coerceAtLeast(1)
                doc.addImageStamp(
                    index, pt.x, pt.y - h, pt.x + w, pt.y, jpeg.width, jpeg.height, jpeg.bytes
                )
                markEdited()
            }
        }
    }

    val shareAction = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
    }

    // "Save": overwrite the original file in place.
    val saveInPlace: () -> Unit = {
        val doc = document
        if (doc != null) {
            scope.launch {
                val bytes = doc.save()
                val ok = bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                android.widget.Toast.makeText(
                    context,
                    if (ok) context.getString(R.string.pdf_saved)
                    else context.getString(R.string.pdf_save_error),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.82f)) {
                Text(
                    "Outline",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(outline) { entry ->
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        if (entry.page >= 0) listState.animateScrollToItem(entry.page)
                                        drawerState.close()
                                    }
                                }
                                .padding(
                                    start = (16 + entry.level * 14).dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                        )
                    }
                }
            }
        },
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            placeholder = { Text(stringResource(R.string.search_label)) },
                            singleLine = true,
                        )
                    } else {
                        Text(stringResource(R.string.safe_pdf_viewer_title))
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconNavigation { searching = false; query = "" }
                    } else if (outline.isNotEmpty()) {
                        IconButton({ scope.launch { drawerState.open() } }) { IconMenu() }
                    } else {
                        IconNavigation { onBack() }
                    }
                },
                actions = {
                    if (searching) {
                        if (matches.isNotEmpty()) {
                            Text(
                                "${matchIndex + 1}/${matches.size}",
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            IconButton({ if (matchIndex > 0) matchIndex-- }) {
                                Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null)
                            }
                            IconButton({ if (matchIndex < matches.size - 1) matchIndex++ }) {
                                Icon(painterResource(R.drawable.keyboard_arrow_down_24px), null)
                            }
                        }
                    } else {
                        if (!editMode) {
                            IconButton({ searching = true }) { IconSearch() }
                        }
                        IconButton({
                            commitText(textSession); textSession = null
                            editMode = !editMode; selected = null
                        }) {
                            if (editMode) IconVisible() else IconEdit()
                        }
                        if (dirty) {
                            Box {
                                IconButton({ showSaveMenu = true }) { IconSave() }
                                DropdownMenu(
                                    expanded = showSaveMenu,
                                    onDismissRequest = { showSaveMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Save") },
                                        onClick = { showSaveMenu = false; saveInPlace() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save as\u2026") },
                                        onClick = {
                                            showSaveMenu = false
                                            saveLauncher.launch(uri.lastPathSegment ?: "edited.pdf")
                                        },
                                    )
                                }
                            }
                        } else {
                            IconButton({ shareAction() }) { IconShare() }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (editMode) {
                EditToolbar(
                    tool = tool,
                    onTool = { tool = it; selected = null },
                    color = color,
                    onColor = { color = it },
                    canDelete = selected != null,
                    onDelete = {
                        val sel = selected
                        val doc = document
                        if (sel != null && doc != null) {
                            scope.launch {
                                doc.deleteAnnotation(sel.first, sel.second)
                                selected = null
                                markEdited()
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = loadState) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                LoadState.Error -> Text(
                    text = stringResource(R.string.safe_pdf_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is LoadState.Loaded -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }
                        .transformable(transformState, enabled = !editMode),
                    state = listState,
                ) {
                    items((0 until state.document.pageCount).toList()) { index ->
                        val pageHighlights = matches.filter { it.first == index }.map { it.second }
                        val current = matches.getOrNull(matchIndex)
                        val currentHighlight = if (current?.first == index) current.second else null
                        SafePdfPageItem(
                            document = state.document,
                            index = index,
                            version = version,
                            editMode = editMode,
                            tool = tool,
                            color = color,
                            selected = selected?.takeIf { it.first == index }?.second,
                            highlights = pageHighlights,
                            currentHighlight = currentHighlight,
                            scope = scope,
                            onSelect = { annotId -> selected = annotId?.let { index to it } },
                            onEdited = markEdited,
                            textSession = textSession?.takeIf { it.page == index },
                            onStartText = { s -> commitText(textSession); textSession = s },
                            onTextChange = { v -> textSession = textSession?.copy(value = v) },
                            onCommitText = { commitText(textSession); textSession = null },
                            onRequestImage = { pt -> imageTarget = index to pt; imageLauncher.launch("image/*") },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SafePdfPageItem(
    document: SafePdfDocument,
    index: Int,
    version: Int,
    editMode: Boolean,
    tool: EditTool,
    color: Color,
    selected: Long?,
    highlights: List<Rect>,
    currentHighlight: Rect?,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    textSession: TextSession?,
    onStartText: (TextSession) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onCommitText: () -> Unit,
    onRequestImage: (Offset) -> Unit,
) {
    val page by produceState<SafePdfPage?>(null, document, index, version) {
        value = document.renderPage(index)
    }
    val annotations by produceState(emptyList<SafeAnnotation>(), document, index, version) {
        value = if (editMode) document.annotations(index) else emptyList()
    }
    val formFields by produceState(emptyList<SafeFormField>(), document, index, version) {
        value = if (editMode) document.formFields(index) else emptyList()
    }

    val current = page
    val ratio = if (current != null && current.height > 0f) current.width / current.height
    else 612f / 792f

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .aspectRatio(ratio)
            .background(Color.White)
            .clipToBounds()
    ) {
        if (current == null || current.width <= 0f) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val scale = cw / current.width

        fun toPage(o: Offset) = Offset(o.x / scale, (ch - o.y) / scale)

        Canvas(Modifier.fillMaxSize()) { drawSafePage(current) }

        if (highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (r in highlights) {
                    val isCurrent = r == currentHighlight
                    drawRect(
                        color = if (isCurrent) Color(0xAAFF9800) else Color(0x66FFEB3B),
                        topLeft = Offset(r.left * scale, ch - r.top * scale),
                        size = Size((r.right - r.left) * scale, (r.top - r.bottom) * scale),
                    )
                }
            }
        }

        if (editMode) {
            EditOverlay(
                page = current,
                annotations = annotations,
                selected = selected,
                tool = tool,
                color = color,
                cw = cw,
                ch = ch,
                scale = scale,
                toPage = ::toPage,
                document = document,
                index = index,
                scope = scope,
                onSelect = onSelect,
                onEdited = onEdited,
                onStartText = onStartText,
                onRequestImage = onRequestImage,
            )
            FormFieldOverlay(
                fields = formFields,
                ch = ch,
                scale = scale,
                document = document,
                index = index,
                scope = scope,
                onEdited = onEdited,
            )

            // Inline text editing: a live text field drawn on the page.
            if (textSession != null) {
                val density = LocalDensity.current
                val focus = remember(textSession.annotId, textSession.origin) { FocusRequester() }
                LaunchedEffect(textSession.annotId, textSession.origin) { focus.requestFocus() }
                val leftDp = with(density) { (textSession.origin.x * scale).toDp() }
                val topDp = with(density) { (ch - textSession.origin.y * scale).toDp() }
                BasicTextField(
                    value = textSession.value,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .widthIn(min = 48.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused) onCommitText() }
                        .background(Color(0x33448AFF)),
                    textStyle = TextStyle(
                        color = Color(textSession.color),
                        fontSize = with(density) { (textSession.size * scale).toSp() },
                    ),
                    cursorBrush = SolidColor(Color(textSession.color)),
                )
            }
        }
    }
}

@Composable
private fun EditOverlay(
    page: SafePdfPage,
    annotations: List<SafeAnnotation>,
    selected: Long?,
    tool: EditTool,
    color: Color,
    cw: Float,
    ch: Float,
    scale: Float,
    toPage: (Offset) -> Offset,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    onStartText: (TextSession) -> Unit,
    onRequestImage: (Offset) -> Unit,
) {
    // In-progress drag shape in screen space.
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var inkPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Accumulated move delta for the selected annotation (screen space).
    var moveDelta by remember { mutableStateOf(Offset.Zero) }

    fun annotAt(screen: Offset): SafeAnnotation? {
        val p = toPage(screen)
        return annotations.lastOrNull { p.x in it.x0..it.x1 && p.y in it.y0..it.y1 }
    }

    val gestures = Modifier.pointerInput(tool, selected, annotations) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val blockScroll =
                tool == EditTool.HIGHLIGHT || tool == EditTool.RECT || tool == EditTool.DRAW
            if (blockScroll) {
                down.consume()
                dragStart = start
                dragCurrent = start
                if (tool == EditTool.DRAW) inkPoints = listOf(start)
            }
            if (tool == EditTool.SELECT) moveDelta = Offset.Zero

            var dragging = false
            var lastPos = start
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val pos = change.position
                if (!dragging && (pos - start).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    if (tool == EditTool.SELECT) onSelect(annotAt(start)?.id)
                }
                if (dragging) {
                    change.consume()
                    when (tool) {
                        EditTool.HIGHLIGHT, EditTool.RECT -> dragCurrent = pos
                        EditTool.DRAW -> { dragCurrent = pos; inkPoints = inkPoints + pos }
                        EditTool.SELECT -> moveDelta += (pos - lastPos)
                        else -> {}
                    }
                }
                lastPos = pos
            }

            if (!dragging) {
                when (tool) {
                    EditTool.TEXT -> {
                        val p = toPage(start)
                        onStartText(
                            TextSession(
                                page = index,
                                origin = p,
                                size = 14f,
                                color = color.toArgb(),
                                annotId = null,
                                value = TextFieldValue("Text", TextRange(0, 4)),
                            )
                        )
                    }
                    EditTool.IMAGE -> onRequestImage(toPage(start))
                    EditTool.SELECT -> {
                        val hit = annotAt(start)
                        if (hit != null && hit.id == selected && hit.subtype == 1) {
                            val sz = ((hit.y1 - hit.y0) / 1.3f).coerceIn(6f, 72f)
                            onStartText(
                                TextSession(
                                    page = index,
                                    origin = Offset(hit.x0, hit.y1),
                                    size = sz,
                                    color = hit.color,
                                    annotId = hit.id,
                                    value = TextFieldValue(hit.contents, TextRange(0, hit.contents.length)),
                                )
                            )
                        } else {
                            onSelect(hit?.id)
                        }
                    }
                    else -> {}
                }
            } else {
                val s = dragStart
                val e = dragCurrent
                when (tool) {
                    EditTool.HIGHLIGHT -> if (s != null && e != null) {
                        val a = toPage(s); val b = toPage(e)
                        scope.launch {
                            document.addHighlight(index, a.x, a.y, b.x, b.y, color.toArgb()); onEdited()
                        }
                    }
                    EditTool.RECT -> if (s != null && e != null) {
                        val a = toPage(s); val b = toPage(e)
                        scope.launch {
                            document.addRect(index, a.x, a.y, b.x, b.y, color.toArgb(), 1.5f); onEdited()
                        }
                    }
                    EditTool.DRAW -> {
                        val pts = inkPoints
                        if (pts.size >= 2) {
                            val flat = FloatArray(pts.size * 2)
                            pts.forEachIndexed { i, o ->
                                val pp = toPage(o); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                            }
                            scope.launch { document.addInk(index, color.toArgb(), 2f, flat); onEdited() }
                        }
                    }
                    EditTool.SELECT -> {
                        val id = selected
                        val a = annotations.firstOrNull { it.id == id }
                        if (id != null && a != null) {
                            val dx = moveDelta.x / scale
                            val dy = -moveDelta.y / scale
                            scope.launch {
                                document.moveAnnotation(
                                    index, id, a.x0 + dx, a.y0 + dy, a.x1 + dx, a.y1 + dy
                                )
                                onEdited()
                            }
                        }
                    }
                    else -> {}
                }
            }
            dragStart = null
            dragCurrent = null
            if (tool == EditTool.DRAW) inkPoints = emptyList()
            moveDelta = Offset.Zero
        }
    }

    Canvas(Modifier.fillMaxSize().then(gestures)) {
        // Selection highlight.
        val sel = annotations.firstOrNull { it.id == selected }
        if (sel != null) {
            val left = sel.x0 * scale + moveDelta.x
            val top = ch - sel.y1 * scale + moveDelta.y
            drawRect(
                color = Color(0xFF2196F3),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size((sel.x1 - sel.x0) * scale, (sel.y1 - sel.y0) * scale),
                style = Stroke(width = 3f),
            )
        }
        // In-progress shapes.
        val s = dragStart
        val e = dragCurrent
        if (s != null && e != null && (tool == EditTool.HIGHLIGHT || tool == EditTool.RECT)) {
            drawRect(
                color = color.copy(alpha = if (tool == EditTool.HIGHLIGHT) 0.35f else 1f),
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = androidx.compose.ui.geometry.Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = if (tool == EditTool.HIGHLIGHT) Fill else Stroke(width = 2f),
            )
        }
        if (tool == EditTool.DRAW && inkPoints.size >= 2) {
            val path = Path().apply {
                moveTo(inkPoints[0].x, inkPoints[0].y)
                inkPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun FormFieldOverlay(
    fields: List<SafeFormField>,
    ch: Float,
    scale: Float,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onEdited: () -> Unit,
) {
    val density = LocalDensity.current
    for (field in fields) {
        val leftDp = with(density) { (field.x0 * scale).toDp() }
        val topDp = with(density) { (ch - field.y1 * scale).toDp() }
        val wDp = with(density) { ((field.x1 - field.x0) * scale).toDp() }
        val hDp = with(density) { ((field.y1 - field.y0) * scale).toDp() }

        when (field.type) {
            0 -> { // text field
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x332196F3)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setTextField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            1 -> { // checkbox / button
                var checked by remember(field.id, field.checked) { mutableStateOf(field.checked) }
                Box(Modifier.padding(start = leftDp, top = topDp).size(wDp, hDp)) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            scope.launch { document.setCheckbox(index, field.id, it); onEdited() }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditToolbar(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    BottomAppBar {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolButton("Sel", tool == EditTool.SELECT) { onTool(EditTool.SELECT) }
            toolButton("Text", tool == EditTool.TEXT) { onTool(EditTool.TEXT) }
            toolButton("HL", tool == EditTool.HIGHLIGHT) { onTool(EditTool.HIGHLIGHT) }
            toolButton("Draw", tool == EditTool.DRAW) { onTool(EditTool.DRAW) }
            toolButton("Rect", tool == EditTool.RECT) { onTool(EditTool.RECT) }
            toolButton("Img", tool == EditTool.IMAGE) { onTool(EditTool.IMAGE) }

            for (c in listOf(Color.Red, Color.Yellow, Color.Blue, Color.Black)) {
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .pointerInput(c) { detectTapGestures { onColor(c) } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c == color) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }

            if (canDelete) {
                IconButton(onDelete) { IconDelete() }
            }
        }
    }
}

@Composable
private fun toolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Draw a page's primitives, mapping PDF page space (origin bottom-left) to the
 * canvas (origin top-left) with a uniform fit-to-width scale + Y-flip.
 */
private fun DrawScope.drawSafePage(page: SafePdfPage) {
    val scale = size.width / page.width
    val h = size.height

    fun map(p: Offset) = Offset(p.x * scale, h - p.y * scale)

    val textPaint = android.graphics.Paint().apply { isAntiAlias = true }
    val imagePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    for (prim in page.primitives) {
        when (prim) {
            is PdfPrimitive.FillPath -> {
                val path = prim.points.toPath(::map) ?: continue
                if (prim.evenOdd) path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                drawPath(path, Color(prim.color), style = Fill)
            }

            is PdfPrimitive.StrokePath -> {
                val path = prim.points.toPath(::map) ?: continue
                val pathEffect = if (prim.dash.size >= 2) {
                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        prim.dash.map { it * scale }.toFloatArray(),
                        prim.dashPhase * scale,
                    )
                } else {
                    null
                }
                drawPath(
                    path,
                    Color(prim.color),
                    style = Stroke(
                        width = (prim.width * scale).coerceAtLeast(1f),
                        pathEffect = pathEffect,
                    ),
                )
            }

            is PdfPrimitive.Text -> {
                if (prim.text.isBlank()) continue
                val origin = map(prim.origin)
                textPaint.color = prim.color
                textPaint.textSize = (prim.size * scale).coerceAtLeast(1f)
                drawContext.canvas.nativeCanvas.drawText(prim.text, origin.x, origin.y, textPaint)
            }

            is PdfPrimitive.Image -> {
                val bmp = prim.bitmap ?: continue
                val m = prim.ctm
                fun unitToCanvas(u: Float, v: Float): Offset {
                    val pageX = m[0] * u + m[2] * v + m[4]
                    val pageY = m[1] * u + m[3] * v + m[5]
                    return map(Offset(pageX, pageY))
                }
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                val src = floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh)
                val c00 = unitToCanvas(0f, 1f)
                val c10 = unitToCanvas(1f, 1f)
                val c11 = unitToCanvas(1f, 0f)
                val c01 = unitToCanvas(0f, 0f)
                val dst = floatArrayOf(c00.x, c00.y, c10.x, c10.y, c11.x, c11.y, c01.x, c01.y)
                val matrix = android.graphics.Matrix()
                matrix.setPolyToPoly(src, 0, dst, 0, 4)
                drawContext.canvas.nativeCanvas.drawBitmap(bmp, matrix, imagePaint)
            }
        }
    }
}

private inline fun List<Offset>.toPath(map: (Offset) -> Offset): Path? {
    if (size < 2) return null
    val path = Path()
    val first = map(this[0])
    path.moveTo(first.x, first.y)
    for (i in 1 until size) {
        val p = map(this[i])
        path.lineTo(p.x, p.y)
    }
    return path
}

private class JpegImage(val bytes: ByteArray, val width: Int, val height: Int)

/** Read [uri] and re-encode it as JPEG for a stamp; null on failure. */
private fun readAsJpeg(context: android.content.Context, uri: Uri): JpegImage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    JpegImage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
