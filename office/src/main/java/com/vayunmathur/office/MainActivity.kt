package com.vayunmathur.office

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Typography
import com.vayunmathur.office.odf.*
import com.vayunmathur.office.ui.*
import com.vayunmathur.office.util.OfficeViewModel
import kotlinx.coroutines.launch

@Composable
private fun OfficeLightTheme(content: @Composable () -> Unit) {
    // Office only supports light mode; always use a light color scheme regardless of the system theme.
    val colorScheme = dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

class MainActivity : ComponentActivity() {
    private val viewModel: OfficeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.loadSettings(this)

        val intentUri: Uri? = intent.data

        setContent {
            val startedWithIntent = intentUri != null
            var documentUri by rememberSaveable { mutableStateOf(intentUri) }
            val state by viewModel.state.collectAsState()

            if (documentUri != null && state is OfficeViewModel.ViewState.Empty) {
                viewModel.loadDocument(documentUri!!, documentUri?.lastPathSegment ?: "document")
            }

            val odfMimeTypes = arrayOf(
                "application/vnd.oasis.opendocument.text",
                "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation",
                "application/vnd.oasis.opendocument.graphics"
            )

            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { documentUri = it; viewModel.loadDocument(it, it.lastPathSegment ?: "document") }
            }

            OfficeLightTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val s = state) {
                        is OfficeViewModel.ViewState.Empty -> InitialScreen(
                            viewModel = viewModel,
                            onOpenDocument = { filePickerLauncher.launch(odfMimeTypes) }
                        )
                        is OfficeViewModel.ViewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        is OfficeViewModel.ViewState.Loaded -> DocumentScreen(
                            document = s.document, viewModel = viewModel, activity = this@MainActivity,
                            onBack = { if (startedWithIntent) finish() else { documentUri = null; viewModel.clear() } }
                        )
                        is OfficeViewModel.ViewState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
                                Text(s.message, Modifier.padding(16.dp))
                                Button(onClick = { documentUri = null; viewModel.clear() }) { Text(stringResource(R.string.open_document)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InitialScreen(viewModel: OfficeViewModel, onOpenDocument: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text("Office", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Open Document Format Viewer & Editor", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        Button(onClick = onOpenDocument, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_document)) }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.createNewTextDocument() }, Modifier.weight(1f)) { Text("New Doc") }
            OutlinedButton(onClick = { viewModel.createNewSpreadsheet() }, Modifier.weight(1f)) { Text("New Sheet") }
            OutlinedButton(onClick = { viewModel.createNewPresentation() }, Modifier.weight(1f)) { Text("New Slides") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(document: OdfDocument, viewModel: OfficeViewModel, activity: ComponentActivity, onBack: () -> Unit) {
    var showMetadata by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFontControl by remember { mutableStateOf(false) }
    var fontSizeMultiplier by remember { mutableFloatStateOf(1f) }
    var activeRunStart by remember { mutableIntStateOf(-1) }
    var activeRunEnd by remember { mutableIntStateOf(-1) }
    var activeTableBlock by remember { mutableIntStateOf(-1) }
    var activeTableRow by remember { mutableIntStateOf(-1) }
    var activeTableCol by remember { mutableIntStateOf(-1) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showReplaceBar by remember { mutableStateOf(false) }
    var replaceText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showFontSizePicker by remember { mutableStateOf(false) }
    var showInsertTable by remember { mutableStateOf(false) }
    var showInsertLink by remember { mutableStateOf(false) }
    var showAddBookmark by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var selStart by remember { mutableIntStateOf(0) }
    var selEnd by remember { mutableIntStateOf(0) }
    var fileMenu by remember { mutableStateOf(false) }
    var editMenu by remember { mutableStateOf(false) }
    var insertMenu by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }

    val isEditMode by viewModel.isEditMode.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val autoSaveEnabled by viewModel.autoSaveEnabled.collectAsState()

    val isTextDoc = document is OdfDocument.TextDocument
    val isSpreadsheet = document is OdfDocument.Spreadsheet
    val isPresentation = document is OdfDocument.Presentation
    val canEdit = isTextDoc || isSpreadsheet || isPresentation
    val focusedPara = if (activeRunStart >= 0 && isTextDoc) viewModel.runParagraphIndexAt(activeRunStart, activeRunEnd, selStart) else -1

    val headings = remember(document) { if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList() }
    val wordCount = remember(document) { if (document is OdfDocument.TextDocument) countWords(document) else 0 }
    val charCount = remember(document) { if (document is OdfDocument.TextDocument) countChars(document) else 0 }
    val readingTime = remember(document) { if (document is OdfDocument.TextDocument) readingTimeMinutes(document) else 0 }
    val bookmarks = remember(document) { if (document is OdfDocument.TextDocument) document.bookmarks else emptyList() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { viewModel.save(it) } }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            val csv = viewModel.exportCsv()
            context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(csv) }
        }
    }

    // Presentation timer
    LaunchedEffect(showTimer) {
        if (showTimer) {
            timerSeconds = 0
            while (showTimer) { kotlinx.coroutines.delay(1000); timerSeconds++ }
        }
    }

    BackHandler(enabled = hasUnsavedChanges) { showUnsavedDialog = true }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty()),
        drawerContent = {
            if (isTextDoc) {
                ModalDrawerSheet(Modifier.width(280.dp)) {
                    Text(stringResource(R.string.outline), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    if (bookmarks.isNotEmpty()) {
                        Text("Bookmarks", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp))
                        LazyColumn(modifier = Modifier.height(150.dp)) {
                            items(bookmarks) { bk ->
                                Text("🔖 ${bk.name}", modifier = Modifier.fillMaxWidth()
                                    .clickable { scope.launch { listState.animateScrollToItem(bk.contentIndex); drawerState.close() } }
                                    .padding(16.dp, 8.dp))
                            }
                        }
                        HorizontalDivider()
                    }
                    LazyColumn {
                        items(headings) { heading ->
                            Text(heading.text,
                                style = when (heading.level) { 1 -> MaterialTheme.typography.titleMedium; 2 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.bodyMedium },
                                fontWeight = if (heading.level <= 2) FontWeight.Bold else null,
                                modifier = Modifier.fillMaxWidth().clickable { scope.launch { listState.animateScrollToItem(heading.contentIndex); drawerState.close() } }
                                    .padding(start = (16 + (heading.level - 1) * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                                maxLines = 2)
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        expandedHeight = 56.dp,
                        title = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = {
                            IconButton(onClick = { if (hasUnsavedChanges) showUnsavedDialog = true else onBack() }) {
                                Icon(painterResource(com.vayunmathur.library.R.drawable.arrow_back_24px), contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.undo() }, enabled = canUndo) { Icon(painterResource(com.vayunmathur.library.R.drawable.undo_24px), contentDescription = "Undo") }
                            IconButton(onClick = { viewModel.redo() }, enabled = canRedo) { Icon(painterResource(R.drawable.redo_24px), contentDescription = "Redo") }
                            IconButton(onClick = { viewModel.save() }, enabled = hasUnsavedChanges && !isSaving) {
                                if (isSaving) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(painterResource(com.vayunmathur.library.R.drawable.save_24px), contentDescription = "Save")
                            }
                        }
                    )
                    // Office-style menu bar
                    Surface(tonalElevation = 2.dp) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                            // File
                            Box {
                                TextButton(onClick = { fileMenu = true }) { Text("File") }
                                DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                                    DropdownMenuItem(text = { Text("Save") }, enabled = hasUnsavedChanges, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.save_24px), null) }, onClick = { fileMenu = false; viewModel.save() })
                                    DropdownMenuItem(text = { Text("Save As…") }, onClick = { fileMenu = false; saveAsLauncher.launch(document.title) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.print_doc)) }, onClick = { fileMenu = false; printDocument(activity, document) })
                                    viewModel.documentUri?.let { uri ->
                                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.share_24px), null) }, onClick = { fileMenu = false; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null)) })
                                    }
                                    DropdownMenuItem(text = { Text("Export as Text") }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.outline_file_download_24), null) }, onClick = { fileMenu = false; val t = viewModel.exportAsPlainText(); if (t.isNotEmpty()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }, null)) })
                                    if (isSpreadsheet) DropdownMenuItem(text = { Text("Export as CSV") }, onClick = { fileMenu = false; csvExportLauncher.launch("export.csv") })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text(stringResource(R.string.document_info)) }, onClick = { fileMenu = false; showMetadata = true })
                                    DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.settings_24px), null) }, onClick = { fileMenu = false; showSettings = true })
                                }
                            }
                            // Edit
                            Box {
                                TextButton(onClick = { editMenu = true }) { Text("Edit") }
                                DropdownMenu(expanded = editMenu, onDismissRequest = { editMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.outline_search_24), null) }, onClick = { editMenu = false; showSearch = true })
                                    if (isTextDoc) {
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("Duplicate paragraph") }, enabled = focusedPara >= 0, onClick = { editMenu = false; viewModel.duplicateParagraph(focusedPara) })
                                        DropdownMenuItem(text = { Text("Move paragraph up") }, enabled = focusedPara > 0, onClick = { editMenu = false; viewModel.moveParagraphUp(focusedPara) })
                                        DropdownMenuItem(text = { Text("Move paragraph down") }, enabled = focusedPara >= 0, onClick = { editMenu = false; viewModel.moveParagraphDown(focusedPara) })
                                        DropdownMenuItem(text = { Text("Delete paragraph", color = MaterialTheme.colorScheme.error) }, enabled = focusedPara >= 0, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.delete_24px), null) }, onClick = { editMenu = false; viewModel.deleteParagraph(focusedPara) })
                                    }
                                }
                            }
                            // Insert
                            if (isTextDoc) Box {
                                TextButton(onClick = { insertMenu = true }) { Text("Insert") }
                                DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                                    DropdownMenuItem(text = { Text("Paragraph") }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.add_24px), null) }, onClick = { insertMenu = false; viewModel.addParagraphAfter(maxOf(0, focusedPara)) })
                                    DropdownMenuItem(text = { Text("Hyperlink") }, onClick = { insertMenu = false; showInsertLink = true })
                                    DropdownMenuItem(text = { Text("Bookmark") }, onClick = { insertMenu = false; showAddBookmark = true })
                                    DropdownMenuItem(text = { Text("Page break") }, onClick = { insertMenu = false; viewModel.insertPageBreak(maxOf(0, focusedPara)) })
                                }
                            }
                            // Format
                            if (isTextDoc) Box {
                                TextButton(onClick = { formatMenu = true }) { Text("Format") }
                                DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.font_size)) }, enabled = focusedPara >= 0, onClick = { formatMenu = false; showFontSizePicker = true })
                                }
                            }
                            // View
                            Box {
                                TextButton(onClick = { viewMenu = true }) { Text("View") }
                                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                    if (isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty())) DropdownMenuItem(text = { Text(stringResource(R.string.outline)) }, onClick = { viewMenu = false; scope.launch { drawerState.open() } })
                                    DropdownMenuItem(text = { Text("Zoom text") }, onClick = { viewMenu = false; showFontControl = !showFontControl })
                                    if (isTextDoc && wordCount > 0) DropdownMenuItem(text = { Text("$wordCount words · $charCount chars · ~${readingTime} min") }, enabled = false, onClick = { })
                                    if (isPresentation) DropdownMenuItem(text = { Text("Presentation timer") }, onClick = { viewMenu = false; showTimer = !showTimer })
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = showSearch) {
                        Column {
                            TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                                trailingIcon = {
                                    Row {
                                        if (isTextDoc) TextButton(onClick = { showReplaceBar = !showReplaceBar }) { Text(if (showReplaceBar) "Hide" else "Replace") }
                                        IconButton(onClick = { showSearch = false; searchQuery = ""; showReplaceBar = false }) { Icon(painterResource(com.vayunmathur.library.R.drawable.close_24px), contentDescription = "Close search") }
                                    }
                                })
                            AnimatedVisibility(visible = showReplaceBar && searchQuery.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextField(value = replaceText, onValueChange = { replaceText = it }, placeholder = { Text(stringResource(R.string.replace_hint)) }, singleLine = true, modifier = Modifier.weight(1f),
                                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                                    TextButton(onClick = { viewModel.replaceInDocument(searchQuery, replaceText, false) }) { Text("One") }
                                    TextButton(onClick = { val n = viewModel.replaceInDocument(searchQuery, replaceText, true); if (n > 0) searchQuery = "" }) { Text("All") }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = showFontControl) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("A", style = MaterialTheme.typography.bodySmall)
                            Slider(value = fontSizeMultiplier, onValueChange = { fontSizeMultiplier = it }, valueRange = 0.5f..2.0f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text("A", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { fontSizeMultiplier = 1f }) { Text(stringResource(R.string.reset)) }
                        }
                    }
                }
            },
            bottomBar = {
                if (isTextDoc) QuickFormatBar(document = document, runStart = activeRunStart, runEnd = activeRunEnd, selStart = selStart, selEnd = selEnd, activeTableBlock = activeTableBlock, activeTableRow = activeTableRow, activeTableCol = activeTableCol, viewModel = viewModel, onColorClick = { showColorPicker = true }, onInsertTable = { showInsertTable = true })
            }
        ) { paddingValues ->
            Box(Modifier.padding(paddingValues)) {
                when (document) {
                    is OdfDocument.TextDocument -> TextDocumentView(doc = document, searchQuery = searchQuery, fontSizeMultiplier = fontSizeMultiplier, listState = listState,
                        onRunSelectionChange = { rs, re, gs, ge -> activeRunStart = rs; activeRunEnd = re; selStart = gs; selEnd = ge },
                        onRunTextChange = { rs, re, text -> viewModel.updateParagraphRun(rs, re, text) },
                        onCellTextChange = { bi, r, c, text -> viewModel.updateTextTableCell(bi, r, c, text) },
                        onCellFocus = { bi, r, c -> activeTableBlock = bi; activeTableRow = r; activeTableCol = c })
                    is OdfDocument.Spreadsheet -> SpreadsheetView(doc = document, searchQuery = searchQuery, fontSizeMultiplier = fontSizeMultiplier, isEditMode = isEditMode,
                        onCellTextChange = { s, r, c, t -> viewModel.updateCellText(s, r, c, t) }, onAddRow = { s, r -> viewModel.addRow(s, r) }, onAddColumn = { s -> viewModel.addColumn(s) },
                        onDeleteRow = { s, r -> viewModel.deleteRow(s, r) }, onDeleteColumn = { s, c -> viewModel.deleteColumn(s, c) },
                        onRenameSheet = { s, n -> viewModel.renameSheet(s, n) }, onAddSheet = { viewModel.addSheet() }, onDeleteSheet = { s -> viewModel.deleteSheet(s) },
                        onCellBold = { s, r, c -> viewModel.setCellBold(s, r, c) }, onCellItalic = { s, r, c -> viewModel.setCellItalic(s, r, c) },
                        onCellColor = { s, r, c, clr -> viewModel.setCellColor(s, r, c, clr) }, onCellBgColor = { s, r, c, clr -> viewModel.setCellBgColor(s, r, c, clr) },
                        onCellAlignment = { s, r, c, a -> viewModel.setCellAlignment(s, r, c, a) },
                        onMergeCells = { s, sr, sc, er, ec -> viewModel.mergeCells(s, sr, sc, er, ec) }, onUnmergeCells = { s, r, c -> viewModel.unmergeCells(s, r, c) },
                        onSort = { s, col, asc -> viewModel.sortRows(s, col, asc) })
                    is OdfDocument.Presentation -> PresentationView(doc = document, isEditMode = isEditMode,
                        onAddSlide = { viewModel.addSlide(it) }, onDeleteSlide = { viewModel.deleteSlide(it) },
                        onDuplicateSlide = { viewModel.duplicateSlide(it) }, onMoveSlideUp = { viewModel.moveSlideUp(it) }, onMoveSlideDown = { viewModel.moveSlideDown(it) })
                    is OdfDocument.Drawing -> DrawingView(document)
                }
            }
        }
    }

    if (showMetadata) MetadataDialog(metadata = document.metadata, onDismiss = { showMetadata = false })
    if (showUnsavedDialog) AlertDialog(onDismissRequest = { showUnsavedDialog = false }, title = { Text(stringResource(R.string.unsaved_changes)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = { TextButton(onClick = { showUnsavedDialog = false; onBack() }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Row { TextButton(onClick = { showUnsavedDialog = false }) { Text(stringResource(R.string.cancel)) }
            if (viewModel.documentUri != null) TextButton(onClick = { viewModel.save(); showUnsavedDialog = false }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) } } })
    if (showSettings) SettingsDialog(autoSave = viewModel.getAutoSaveEnabled(context), autoSaveInterval = viewModel.getAutoSaveInterval(context),
        defaultFontSize = viewModel.getDefaultFontSize(context),
        onSave = { a, i, f -> viewModel.saveSettings(context, a, i, f) }, onDismiss = { showSettings = false })
    if (showColorPicker) ColorPickerDialog("Text Color", onColorSelected = { c -> if (activeRunStart >= 0) viewModel.applyRunSpanStyle(activeRunStart, activeRunEnd, selStart, selEnd) { it.copy(color = c) } }, onDismiss = { showColorPicker = false })
    if (showFontSizePicker) FontSizePickerDialog(onSizeSelected = { sz -> if (activeRunStart >= 0) viewModel.applyRunSpanStyle(activeRunStart, activeRunEnd, selStart, selEnd) { it.copy(fontSize = sz) } }, onDismiss = { showFontSizePicker = false })
    if (showInsertTable) InsertTableDialog(onInsert = { r, c -> viewModel.insertTable(maxOf(0, focusedPara), r, c) }, onDismiss = { showInsertTable = false })
    if (showInsertLink) InsertHyperlinkDialog(onInsert = { t, u -> viewModel.insertHyperlink(maxOf(0, focusedPara), t, u) }, onDismiss = { showInsertLink = false })
    if (showAddBookmark) AddBookmarkDialog(onAdd = { viewModel.addBookmark(it, maxOf(0, focusedPara)) }, onDismiss = { showAddBookmark = false })
}

@Composable
private fun QuickFormatBar(document: OdfDocument, runStart: Int, runEnd: Int, selStart: Int, selEnd: Int, activeTableBlock: Int, activeTableRow: Int, activeTableCol: Int, viewModel: OfficeViewModel, onColorClick: () -> Unit, onInsertTable: () -> Unit) {
    val doc = document as? OdfDocument.TextDocument ?: return
    val enabled = runStart >= 0
    val focusedPara = if (enabled) viewModel.runParagraphIndexAt(runStart, runEnd, selStart) else -1
    val para = (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
    var styleMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }
    var tableMenu by remember { mutableStateOf(false) }

    val isBold = enabled && viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.bold }
    val isItalic = enabled && viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.italic }
    val isUnderline = enabled && viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.underline }
    val isStrike = enabled && viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.strikethrough }
    val isBullet = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.BULLET
    val isNumbered = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED

    val styleLabel = when (para?.style) {
        ParagraphStyle.HEADING1 -> "H1"; ParagraphStyle.HEADING2 -> "H2"; ParagraphStyle.HEADING3 -> "H3"; ParagraphStyle.HEADING4 -> "H4"; else -> "Normal"
    }
    val alignIcon = when (para?.alignment) {
        TextAlign.Center -> R.drawable.format_align_center_24px
        TextAlign.End, TextAlign.Right -> R.drawable.format_align_right_24px
        TextAlign.Justify -> R.drawable.format_align_justify_24px
        else -> R.drawable.format_align_left_24px
    }
    fun setStyle(s: ParagraphStyle) { viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(style = s) } }
    fun setAlign(a: TextAlign) { viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(alignment = a) } }

    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { styleMenu = true }, enabled = enabled) {
                    Text(styleLabel)
                    Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = "Paragraph style")
                }
                DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                    DropdownMenuItem(text = { Text("Normal") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.BODY) })
                    DropdownMenuItem(text = { Text("Heading 1") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING1) })
                    DropdownMenuItem(text = { Text("Heading 2") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING2) })
                    DropdownMenuItem(text = { Text("Heading 3") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING3) })
                    DropdownMenuItem(text = { Text("Heading 4") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING4) })
                }
            }
            FmtIcon(R.drawable.format_bold_24px, isBold, enabled, "Bold") { val t = !isBold; viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(bold = t) } }
            FmtIcon(R.drawable.format_italic_24px, isItalic, enabled, "Italic") { val t = !isItalic; viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(italic = t) } }
            FmtIcon(R.drawable.format_underlined_24px, isUnderline, enabled, "Underline") { val t = !isUnderline; viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(underline = t) } }
            FmtIcon(R.drawable.format_strikethrough_24px, isStrike, enabled, "Strikethrough") { val t = !isStrike; viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(strikethrough = t) } }
            FmtIcon(R.drawable.format_color_text_24px, false, enabled, "Text color") { onColorClick() }
            Box {
                FmtIcon(alignIcon, false, enabled, "Alignment") { alignMenu = true }
                DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
                    DropdownMenuItem(text = { Text("Left") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_left_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.Start) })
                    DropdownMenuItem(text = { Text("Center") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_center_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.Center) })
                    DropdownMenuItem(text = { Text("Right") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_right_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.End) })
                    DropdownMenuItem(text = { Text("Justify") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_justify_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.Justify) })
                }
            }
            FmtIcon(R.drawable.format_list_bulleted_24px, isBullet, enabled, "Bulleted list") { if (focusedPara >= 0) viewModel.toggleListItem(focusedPara) }
            FmtIcon(R.drawable.format_list_numbered_24px, isNumbered, enabled, "Numbered list") { if (focusedPara >= 0) viewModel.toggleNumberedList(focusedPara) }
            FmtIcon(R.drawable.format_indent_increase_24px, false, enabled, "Increase indent") { if (focusedPara >= 0) viewModel.indentParagraph(focusedPara) }
            FmtIcon(R.drawable.format_indent_decrease_24px, false, enabled, "Decrease indent") { if (focusedPara >= 0) viewModel.outdentParagraph(focusedPara) }
            Box {
                val tableEnabled = activeTableBlock >= 0
                TextButton(onClick = { tableMenu = true }) {
                    Text("Table")
                    Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = "Table options")
                }
                DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
                    DropdownMenuItem(text = { Text("Insert table") }, onClick = { tableMenu = false; onInsertTable() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Insert row below") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableAddRow(activeTableBlock, activeTableRow) })
                    DropdownMenuItem(text = { Text("Insert column right") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableAddColumn(activeTableBlock, activeTableCol) })
                    DropdownMenuItem(text = { Text("Delete row") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableDeleteRow(activeTableBlock, activeTableRow) })
                    DropdownMenuItem(text = { Text("Delete column") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableDeleteColumn(activeTableBlock, activeTableCol) })
                }
            }
        }
    }
}

@Composable private fun FmtIcon(res: Int, active: Boolean, enabled: Boolean, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(res), contentDescription = desc, tint = if (active && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
private fun MetadataDialog(metadata: OdfMetadata, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.document_info)) },
        text = {
            Column {
                metadata.title?.let { MetadataRow(stringResource(R.string.meta_title), it) }
                metadata.author?.let { MetadataRow(stringResource(R.string.meta_author), it) }
                metadata.creator?.let { MetadataRow(stringResource(R.string.meta_creator), it) }
                metadata.creationDate?.let { MetadataRow(stringResource(R.string.meta_created), it) }
                metadata.modifiedDate?.let { MetadataRow(stringResource(R.string.meta_modified), it) }
                metadata.subject?.let { MetadataRow(stringResource(R.string.meta_subject), it) }
                metadata.description?.let { MetadataRow(stringResource(R.string.meta_description), it) }
                if (metadata.keywords.isNotEmpty()) MetadataRow(stringResource(R.string.meta_keywords), metadata.keywords.joinToString(", "))
                metadata.pageCount?.let { MetadataRow(stringResource(R.string.meta_pages), it.toString()) }
                metadata.wordCount?.let { MetadataRow(stringResource(R.string.meta_words), it.toString()) }
                metadata.fileSize?.let { MetadataRow("File Size", formatFileSize(it)) }
                if (listOfNotNull(metadata.title, metadata.author, metadata.creator, metadata.creationDate, metadata.modifiedDate, metadata.subject, metadata.description, metadata.pageCount, metadata.wordCount, metadata.fileSize).isEmpty() && metadata.keywords.isEmpty())
                    Text(stringResource(R.string.meta_none), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable private fun MetadataRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) { Text("$label: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium) }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

// --- Print ---

private fun printDocument(activity: ComponentActivity, document: OdfDocument) {
    val printManager = activity.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager ?: return
    val html = documentToHtml(document)
    val webView = WebView(activity)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            @Suppress("DEPRECATION")
            val printAdapter = webView.createPrintDocumentAdapter(document.title)
            printManager.print(document.title, printAdapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
}

private fun documentToHtml(document: OdfDocument): String {
    val sb = StringBuilder()
    sb.append("""<!DOCTYPE html><html><head><meta charset="UTF-8"><style>body{font-family:sans-serif;padding:16px}table{border-collapse:collapse;width:100%;margin:8px 0}td,th{border:1px solid #ccc;padding:6px 8px}h1,h2,h3,h4{margin:8px 0}.slide{border:1px solid #ccc;padding:24px;margin:16px 0;background:#f9f9f9}</style></head><body>""")
    when (document) {
        is OdfDocument.TextDocument -> {
            for (block in document.content) when (block) {
                is OdfContentBlock.Paragraph -> {
                    val tag = when (block.paragraph.style) { ParagraphStyle.HEADING1 -> "h1"; ParagraphStyle.HEADING2 -> "h2"; ParagraphStyle.HEADING3 -> "h3"; ParagraphStyle.HEADING4 -> "h4"; ParagraphStyle.LIST_ITEM -> "li"; else -> "p" }
                    sb.append("<$tag>")
                    for (span in block.paragraph.spans) { var s = span.text.replace("&", "&amp;").replace("<", "&lt;"); if (span.bold) s = "<b>$s</b>"; if (span.italic) s = "<i>$s</i>"; if (span.underline) s = "<u>$s</u>"; if (span.strikethrough) s = "<s>$s</s>"; if (span.href != null) s = """<a href="${span.href}">$s</a>"""; sb.append(s) }
                    sb.append("</$tag>")
                }
                is OdfContentBlock.Table -> { sb.append("<table>"); for (row in block.table.rows) { sb.append("<tr>"); for (cell in row.cells) { if (cell.isCovered) continue; sb.append("<td"); if (cell.colSpan > 1) sb.append(""" colspan="${cell.colSpan}""""); if (cell.rowSpan > 1) sb.append(""" rowspan="${cell.rowSpan}""""); sb.append(">"); for (para in cell.paragraphs) for (span in para.spans) sb.append(span.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</td>") }; sb.append("</tr>") }; sb.append("</table>") }
                is OdfContentBlock.Image -> sb.append("<p>[Image]</p>")
                is OdfContentBlock.Chart -> sb.append("<p>[Chart]</p>")
                is OdfContentBlock.PageBreak -> sb.append("""<hr style="page-break-after:always">""")
            }
        }
        is OdfDocument.Spreadsheet -> { for (sheet in document.sheets) { sb.append("<h2>${sheet.name}</h2><table>"); for (row in sheet.rows) { sb.append("<tr>"); for (cell in row.cells) { if (cell.isCovered) continue; sb.append("<td"); if (cell.spannedColumns > 1) sb.append(""" colspan="${cell.spannedColumns}""""); sb.append(">${cell.text.replace("&", "&amp;").replace("<", "&lt;")}</td>") }; sb.append("</tr>") }; sb.append("</table>") } }
        is OdfDocument.Presentation -> { for (slide in document.slides) { sb.append("""<div class="slide"><b>${slide.name}</b>"""); for (el in slide.elements) when (el) { is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") }; is OdfSlideElement.Shape -> for (p in el.shape.text) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") } }; sb.append("</div>") } }
        is OdfDocument.Drawing -> { for (page in document.pages) { sb.append("""<div class="slide"><b>${page.name}</b>"""); for (el in page.elements) when (el) { is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") }; is OdfSlideElement.Shape -> for (p in el.shape.text) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") } }; sb.append("</div>") } }
    }
    sb.append("</body></html>")
    return sb.toString()
}
