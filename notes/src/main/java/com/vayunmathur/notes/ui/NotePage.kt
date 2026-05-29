package com.vayunmathur.notes.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.util.NotesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    notesViewModel: NotesViewModel,
    noteID: Long,
) {
    var note by viewModel.getEditable<Note>(noteID) { Note(0, "", "") }

    if (noteID != 0L && note.id == 0L) return

    var isEditing by remember { mutableStateOf(true) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    val focusRequestor = remember { FocusRequester() }

    val searchResultsCount by remember(note.content, searchText) {
        derivedStateOf {
            notesViewModel.searchResultsCount(note.content, searchText)
        }
    }

    BackHandler(enabled = showSearchBar) {
        showSearchBar = false
    }

    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
        }
    }

    val context = LocalContext.current

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
                    placeholder = { Text("Search") },
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
            IconNavigation {
                if (showSearchBar) {
                    showSearchBar = false
                } else {
                    backStack.pop()
                }
            }
        }, actions = {
            if (!showSearchBar) {
                if (!isEditing) {
                    IconButton({ showSearchBar = true }) { IconSearch() }
                }
                IconButton({
                    isEditing = !isEditing
                }) {
                    if (isEditing) IconVisible() else IconEdit()
                }
                IconButton({
                    notesViewModel.requestShare(note.title, note.content)
                }) {
                    IconShare()
                }
                IconButton(onClick = {
                    viewModel.delete(note)
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
    }) { paddingValues ->
        LazyColumn(contentPadding = paddingValues + PaddingValues(horizontal = 16.dp) + PaddingValues(bottom = 16.dp)) {
            item {
                BasicTextField(
                    note.title,
                    { note = note.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = !isEditing,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.title.isEmpty()) Text(
                                text = "Title",
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
                val noMarkers by remember(note.content, searchText, searchIndex, showSearchBar) {
                    derivedStateOf {
                        TextFieldValue(
                            notesViewModel.parseDisplay(
                                note.content,
                                searchQuery = if (showSearchBar) searchText else "",
                                searchIndex = if (showSearchBar) searchIndex else -1,
                            )
                        )
                    }
                }
                BasicTextField(
                    if (isEditing) contentValue else noMarkers,
                    {
                        note = note.copy(content = it.text)
                        contentValue = it.copy(annotatedString = notesViewModel.parseDisplay(it.text))
                    },
                    Modifier.fillMaxSize(),
                    readOnly = !isEditing,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.content.isEmpty()) Text(
                                text = "Content",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}
