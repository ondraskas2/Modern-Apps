package com.vayunmathur.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDatabase
import com.vayunmathur.notes.ui.NotePage
import com.vayunmathur.notes.ui.NotesListPage
import com.vayunmathur.notes.util.NotesViewModel
import com.vayunmathur.notes.util.NotesViewModelFactory
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: DatabaseViewModel
    private val notesViewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(application, viewModel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<NoteDatabase>(dbName = "notes-db")
        viewModel = DatabaseViewModel(db, Note::class to db.noteDao())

        handleIntent(intent)

        setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize().onFileDrop { uris ->
                    notesViewModel.importFiles(uris)
                }) {
                    Navigation(viewModel, notesViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val uris = IntentHelper.getUrisFromIntent(it)
            if (uris.isNotEmpty()) {
                notesViewModel.importFiles(uris)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object NotesList: Route
    @Serializable
    data class Note(val id: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel, notesViewModel: NotesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.NotesList)
    MainNavigation(backStack) {
        entry<Route.NotesList>(metadata = ListPage()) {
            NotesListPage(backStack, viewModel)
        }
        entry<Route.Note>(metadata = ListDetailPage()) {
            NotePage(backStack, viewModel, notesViewModel, it.id)
        }
    }
}
