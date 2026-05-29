package com.vayunmathur.pdf.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
            // Only toast for writes targeting URIs other than the in-place autosave
            // (the autosave writes to pdfDocument.uri and should be silent).
            if (result.targetUri == pdfDocument.uri) return@collect
            val msg = if (result.success) pdfSavedMessage else pdfSaveErrorMessage
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    var showSearchBar by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<PdfRect>()) }
    var searchIndex by remember(searchResults) { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }

    BackHandler {
        if (showSearchBar) {
            showSearchBar = false
            searchResults = emptyList()
        } else {
            onBack()
        }
    }

    LaunchedEffect(pdfDocument.uri) {
        coroutineScope.launch {
            delay(500)
            val restored = PdfStateStore.restore(context, pdfDocument.uri)
            if (restored != null) {
                restored(pdfState)
            }
        }
    }

    var center by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            PdfStateStore.save(context, pdfDocument.uri, center, pdfState)
        }
    }

    fun search() {
        coroutineScope.launch {
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
    }

    var changesMade by remember { mutableStateOf(false) }

    LaunchedEffect(searchResults, searchIndex) {
        pdfState.setHighlights(
            searchResults.mapIndexed { idx, it ->
                Highlight(
                    it, if (idx == searchIndex) 0xFFFFA500.toInt() else Color.Yellow.toArgb()
                )
            })
        if (searchResults.isNotEmpty()) {
            pdfState.scrollToPosition(
                searchResults[searchIndex].let { PdfPoint(it.pageNum, it.left, it.top) })
        }
    }

    val focusRequestor = remember { FocusRequester() }
    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
            search()
        } else {
            searchResults = emptyList()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(
                title = {
                    if (showSearchBar) {
                        TextField(
                            value = searchText,
                            onValueChange = {
                                searchText = it
                                search()
                            },
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
                    IconNavigation {
                        if (showSearchBar) {
                            showSearchBar = false
                            searchResults = emptyList()
                        } else {
                            onBack()
                        }
                    }
                },
                actions = {
                    if (!showSearchBar) {
                        IconButton({ showSearchBar = true }) { IconSearch() }
                        IconButton({ downloadLauncher.launch(pdfName) }) { IconSave() }
                        IconButton({
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, pdfDocument.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    intent, resources.getString(R.string.share_pdf)
                                )
                            )
                        }) { IconShare() }
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
        }, bottomBar = {}, floatingActionButton = {
            Column(Modifier.imePadding()) {
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
                if (changesMade) {
                    FloatingActionButton({
                        changesMade = false
                        viewModel.saveDocumentChanges(pdfDocument, pdfDocument.uri)
                    }) { IconSave() }
                }
            }
        }, contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Box(Modifier.fillMaxSize()) {
                PdfViewer(
                    pdfDocument = pdfDocument,
                    state = pdfState,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        center = coordinates.size.center.toOffset()
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
                            changesMade = true
                        }
                    },
                ) { uri ->
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                    true
                }
            }
        }
    }
}
