package com.vayunmathur.notes.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.findCheckboxPositions
import com.vayunmathur.library.ui.tryToggleCheckbox
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.util.NotesViewModel
import kotlinx.coroutines.launch

// Markdown editing helpers now live in the shared :library:ui module
// (com.vayunmathur.library.ui.MarkdownEditor) so notes and email share them.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePage(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    noteID: Long,
) {
    var note by notesViewModel.editableNote(noteID) { Note(0, "", "") }

    if (noteID != 0L && note.id == 0L) return

    var showSearchBar by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    val focusRequestor = remember { FocusRequester() }

    val searchResultsCount = remember(note.content, searchText) {
        notesViewModel.searchResultsCount(note.content, searchText)
    }

    BackHandler(enabled = showSearchBar) {
        if (searchText.isNotEmpty()) {
            searchText = ""
            searchIndex = 0
        } else {
            showSearchBar = false
        }
    }

    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
        }
    }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(notesViewModel) {
        notesViewModel.shareUris.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Note"))
        }
    }

    var contentValue by remember(noteID) {
        mutableStateOf(TextFieldValue(notesViewModel.parseDisplay(note.content)))
    }

    var contentFocused by remember { mutableStateOf(false) }

    fun applyFormat(transform: (TextFieldValue) -> TextFieldValue) {
        contentValue = transform(contentValue)
        note = note.copy(content = contentValue.text)
        contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            if (showSearchBar) {
                TextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        searchIndex = 0
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequestor),
                    placeholder = { Text(stringResource(R.string.search)) },
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
            }
        }, navigationIcon = {
            Row {
                IconNavigation {
                    if (showSearchBar) {
                        showSearchBar = false
                    } else {
                        backStack.pop()
                    }
                }
            }
        }, actions = {
            if (!showSearchBar) {
                IconButton({ showSearchBar = true }) { IconSearch() }
                IconButton({
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("note", note.content)))
                    }
                }) {
                    IconCopy()
                }
                IconButton({
                    notesViewModel.requestShare(note.title, note.content)
                }) {
                    IconShare()
                }
                IconButton(onClick = {
                    notesViewModel.delete(note)
                    backStack.pop()
                }) {
                    IconDelete()
                }
            } else {
                if (searchResultsCount > 0) {
                    Text(
                        "${searchIndex + 1} / $searchResultsCount",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        })
    }, floatingActionButton = {
        if (showSearchBar) {
            Column(Modifier.imePadding()) {
                SmallFloatingActionButton({ if (searchIndex > 0) searchIndex-- }) {
                    Icon(painterResource(LibraryR.drawable.chevron_right_24px), null, modifier = Modifier.rotate(-90f))
                }
                SmallFloatingActionButton({
                    if (searchIndex < searchResultsCount - 1) searchIndex++
                }) {
                    Icon(painterResource(LibraryR.drawable.chevron_right_24px), null, modifier = Modifier.rotate(90f))
                }
            }
        }
    }, bottomBar = {
        if (!showSearchBar && contentFocused) {
            com.vayunmathur.library.ui.MarkdownFormatToolbar(
                value = contentValue,
                onValueChange = { nv -> applyFormat { nv } },
            )
        }
    }) { paddingValues ->
        LazyColumn(contentPadding = paddingValues + PaddingValues(horizontal = 16.dp) + PaddingValues(bottom = 16.dp)) {
            item {
                BasicTextField(
                    note.title,
                    { note = note.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.title.isEmpty()) Text(
                                text = stringResource(R.string.title),
                                style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            innerTextField()
                        }
                    }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                val displayValue = remember(note.content, searchText, searchIndex, showSearchBar, contentValue) {
                    if (showSearchBar) {
                        TextFieldValue(
                            notesViewModel.parseDisplay(
                                note.content,
                                searchQuery = searchText,
                                searchIndex = searchIndex,
                            ),
                            selection = contentValue.selection,
                        )
                    } else {
                        contentValue
                    }
                }
                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                val density = LocalDensity.current
                Box {
                    BasicTextField(
                        displayValue,
                        { newValue ->
                            if (newValue.text == contentValue.text && newValue.selection.collapsed) {
                                tryToggleCheckbox(newValue.selection.start, contentValue)?.let {
                                    applyFormat { _ -> it }
                                    return@BasicTextField
                                }
                            }
                            applyFormat { _ -> newValue }
                        },
                        Modifier.fillMaxSize().onFocusChanged { contentFocused = it.isFocused },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = LocalContentColor.current,
                            // Constant line height for every line — including empty ones.
                            // Without Trim.None, each line is its own paragraph and gets
                            // its top/bottom leading trimmed, so an empty line is shorter
                            // than a line with text; typing then grows the line and shoves
                            // the rest of the note down. Trim.None keeps all lines equal.
                            lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f,
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.None,
                            ),
                        ),
                        cursorBrush = SolidColor(LocalContentColor.current),
                        onTextLayout = { textLayoutResult = it },
                        decorationBox = { innerTextField ->
                            Box {
                                if (note.content.isEmpty()) Text(
                                    text = stringResource(R.string.content),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                innerTextField()
                            }
                        }
                    )
                    textLayoutResult?.let { layout ->
                        val checkboxes = remember(note.content) { findCheckboxPositions(note.content) }
                        // Size and place the overlay from the text baseline + font size
                        // rather than the measured line box. A blank line before a list
                        // item makes getBoundingBox report a taller box (extra leading on
                        // the paragraph's first/last line), which previously mis-sized and
                        // shifted the icon. The baseline tracks the glyphs, so the checkbox
                        // stays aligned regardless of preceding blank lines.
                        val fontSizePx = with(density) {
                            MaterialTheme.typography.bodyMedium.fontSize.toPx()
                        }
                        // Center the checkbox in its line box. Line boxes are a
                        // constant height (Trim.None) with the text centered in them,
                        // so the line-box center matches the text center for every
                        // row — including the very first line when a note starts
                        // with a checkbox. This avoids baseline math that behaved
                        // differently on the first line.
                        val iconSizePx = fontSizePx * 1.25f
                        checkboxes.forEach { (bracketOffset, isChecked) ->
                            val line = runCatching { layout.getLineForOffset(bracketOffset) }
                                .getOrNull() ?: return@forEach
                            val left = layout.getHorizontalPosition(bracketOffset, usePrimaryDirection = true)
                            val lineCenterY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                            with(density) {
                                Icon(
                                    imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = if (isChecked) "Checked" else "Unchecked",
                                    tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .offset(
                                            x = left.toDp() - 12.dp,
                                            y = (lineCenterY - iconSizePx / 2f).toDp(),
                                        )
                                        .size(iconSizePx.toDp())
                                        .clickable {
                                            tryToggleCheckbox(bracketOffset, contentValue)?.let {
                                                applyFormat { _ -> it }
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
}
