package com.vayunmathur.pdf.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.core.util.forEach
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.compose.FastScrollConfiguration
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.view.Highlight
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.PdfStateStore
import com.vayunmathur.pdf.util.PdfViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfDocument: EditablePdfDocument,
    pdfName: String,
    viewModel: PdfViewModel,
    onBack: () -> Unit,
) {
    val pdfState = remember { PdfViewerState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val linkDestinations by viewModel.linkDestinations.collectAsState()
    val outlineEntries by viewModel.outlineEntries.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(pdfDocument) {
        viewModel.buildLinkIndex(pdfDocument)
        viewModel.buildOutline(pdfDocument)
    }

    val pdfSavedMessage = stringResource(R.string.pdf_saved)
    val pdfSaveErrorMessage = stringResource(R.string.pdf_save_error)
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            viewModel.saveDocumentChanges(pdfDocument, it)
        }
    }

    LaunchedEffect(pdfDocument) {
        viewModel.pdfWriteResults.collect { result ->
            val msg = if (result.success) pdfSavedMessage else pdfSaveErrorMessage
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    var showSearchBar by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<PdfRect>()) }
    var searchIndex by remember(searchResults) { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var showSaveMenu by remember { mutableStateOf(false) }
    var viewerLaidOut by remember { mutableStateOf(false) }
    var centerOffset by remember { mutableStateOf(Offset.Zero) }

    BackHandler {
        when {
            drawerState.isOpen -> coroutineScope.launch { drawerState.close() }
            showSearchBar -> {
                showSearchBar = false
                searchResults = emptyList()
            }
            else -> onBack()
        }
    }

    LaunchedEffect(pdfDocument.uri, viewerLaidOut) {
        if (!viewerLaidOut) return@LaunchedEffect
        PdfStateStore.restore(context, pdfDocument.uri)?.invoke(pdfState)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            PdfStateStore.save(context, pdfDocument.uri, centerOffset, pdfState)
        }
    }

    LaunchedEffect(searchText) {
        if (searchText.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        val results = pdfDocument.searchDocument(searchText, 0 until pdfDocument.pageCount)
        val resultsFinal = mutableListOf<PdfRect>()
        results.forEach { page, result ->
            resultsFinal.addAll(
                result.mapNotNull {
                    it.bounds.firstOrNull()?.let { rect -> PdfRect(page, rect) }
                })
        }
        searchResults = resultsFinal
    }

    LaunchedEffect(searchResults, searchIndex) {
        val allHighlights = searchResults.mapIndexed { idx, it ->
            Highlight(
                it, if (idx == searchIndex) 0xFFFFA500.toInt() else Color.Yellow.toArgb()
            )
        }
        pdfState.setHighlights(allHighlights)
        if (searchResults.isNotEmpty()) {
            pdfState.scrollToPosition(
                searchResults[searchIndex].let { PdfPoint(it.pageNum, it.left, it.top) })
        }
    }

    val focusRequestor = remember { FocusRequester() }
    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
        } else {
            searchResults = emptyList()
        }
    }

    val shareAction = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfDocument.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, resources.getString(R.string.share_pdf))
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                Text(
                    "Outline",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                LazyColumn {
                    itemsIndexed(outlineEntries) { _, entry ->
                        Text(
                            text = entry.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch {
                                        pdfState.scrollToPage(entry.pageNum)
                                        drawerState.close()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        if (showSearchBar) {
                            TextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequestor),
                                placeholder = { Text(stringResource(R.string.search_label)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )
                        } else {
                            Text(stringResource(R.string.pdf_viewer_title))
                        }
                    },
                    navigationIcon = {
                        if (showSearchBar) {
                            IconNavigation {
                                showSearchBar = false
                                searchResults = emptyList()
                            }
                        } else {
                            IconButton({ coroutineScope.launch { drawerState.open() } }) {
                                IconMenu()
                            }
                        }
                    },
                    actions = {
                        if (!showSearchBar) {
                            IconButton({ showSearchBar = true }) { IconSearch() }
                            Box {
                                IconButton(onClick = { showSaveMenu = true }) {
                                    IconSave()
                                }
                                DropdownMenu(
                                    expanded = showSaveMenu,
                                    onDismissRequest = { showSaveMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.pdf_save)) },
                                        onClick = {
                                            showSaveMenu = false
                                            viewModel.saveDocumentChanges(pdfDocument, pdfDocument.uri)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.pdf_save_as_copy)) },
                                        onClick = {
                                            showSaveMenu = false
                                            downloadLauncher.launch(pdfName)
                                        }
                                    )
                                }
                            }
                            IconButton({ shareAction() }) { IconShare() }
                        } else {
                            if (searchResults.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.search_result_counter,
                                        searchIndex + 1,
                                        searchResults.size
                                    ), modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                        }
                    })
            },
            floatingActionButton = {
                if (showSearchBar) {
                    Column {
                        SmallFloatingActionButton({ if (searchIndex > 0) searchIndex-- }) {
                            Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null)
                        }
                        SmallFloatingActionButton({
                            if (searchIndex < searchResults.size - 1) searchIndex++
                        }) { Icon(painterResource(R.drawable.keyboard_arrow_down_24px), null) }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0)
        ) { innerPadding ->
            Box(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                PdfViewer(
                    pdfDocument = pdfDocument,
                    state = pdfState,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        centerOffset = coordinates.size.center.toOffset()
                        viewerLaidOut = true
                    },
                    fastScrollConfig = FastScrollConfiguration.withDrawableIdsAndDp(
                        fastScrollPageIndicatorBackgroundDrawableRes = R.drawable.pdf_page_indicator_background,
                        fastScrollVerticalThumbDrawableRes = R.drawable.pdf_fast_scroll_thumb,
                        fastScrollPageIndicatorMarginEnd = 42.dp,
                        fastScrollVerticalThumbMarginEnd = 0.dp,
                    ),
                    isFormFillingEnabled = true,
                    isImageSelectionEnabled = true,
                    onFormWidgetInfoUpdated = { editInfo ->
                        coroutineScope.launch {
                            pdfDocument.applyEdit(editInfo)
                        }
                    },
                ) { uri ->
                    Log.d("PdfViewer", "Link clicked: uri=$uri scheme=${uri.scheme} fragment=${uri.fragment}")
                    if (uri.scheme == "file") {
                        val destPage = linkDestinations[uri.toString()]
                        if (destPage != null) {
                            Log.d("PdfViewer", "Resolved via index: page $destPage")
                            coroutineScope.launch { pdfState.scrollToPage(destPage) }
                            return@PdfViewer true
                        }
                        val pathOnly = uri.buildUpon().fragment(null).build().toString()
                        val fallback = linkDestinations.entries
                            .firstOrNull { it.key.startsWith(pathOnly) }?.value
                        if (fallback != null) {
                            Log.d("PdfViewer", "Resolved via path match: page $fallback")
                            coroutineScope.launch { pdfState.scrollToPage(fallback) }
                            return@PdfViewer true
                        }
                        Log.d("PdfViewer", "Unresolved internal link: $uri")
                        return@PdfViewer true
                    }
                    val fragment = uri.fragment
                    if (fragment != null) {
                        val page = Regex("page=(\\d+)").find(fragment)
                            ?.groupValues?.get(1)?.toIntOrNull()
                            ?: fragment.toIntOrNull()
                        if (page != null && page in 1..pdfDocument.pageCount) {
                            coroutineScope.launch { pdfState.scrollToPage(page - 1) }
                            return@PdfViewer true
                        }
                    }
                    if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    }
                    true
                }
            }
        }
    }
}
