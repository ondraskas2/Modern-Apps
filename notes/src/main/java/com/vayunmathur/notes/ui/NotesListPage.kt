package com.vayunmathur.notes.ui

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.util.DatabaseHelper
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.ListPageR
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note

@Composable
fun NotesListPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current

    ListPageR<Note, Route, Route.Note>(
        backStack = backStack,
        viewModel = viewModel,
        title = "Notes",
        headlineContent = {
            Text(it.title)
        },
        supportingContent = {
            Text(it.content.substringBefore('\n').take(40))
        },
        viewPage = { Route.Note(it) },
        editPage = { Route.Note(0) },
        searchEnabled = true,
        otherActions = {
            val pass = remember { DatabaseHelper(context).getPassphrase() }
            BackupButtons(
                dbConfigs = listOf("passwords-db" to pass),
                extraFiles = emptyList()
            )
        },
        selectionActions = { selectedNotes, clearSelection ->
            IconButton(onClick = {
                selectedNotes.forEach { viewModel.delete(it) }
                clearSelection()
            }) {
                IconDelete()
            }
        }
    )
}
