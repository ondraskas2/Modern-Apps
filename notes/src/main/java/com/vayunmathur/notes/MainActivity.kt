package com.vayunmathur.notes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: DatabaseViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<NoteDatabase>(dbName = "notes-db")
        viewModel = DatabaseViewModel(db, Note::class to db.noteDao())
        
        handleIntent(intent)

        setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize().onFileDrop { uris ->
                    importFiles(uris)
                }) {
                    Navigation(viewModel)
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
                importFiles(uris)
            }
        }
    }

    private fun importFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.let { content ->
                    val fileName = IntentHelper.getFileName(this, uri) ?: "Imported Note"
                    viewModel.upsertAsync(Note(0, fileName, content))
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error importing file: $uri", e)
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
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.NotesList)
    MainNavigation(backStack) {
        entry<Route.NotesList>(metadata = ListPage()) {
            NotesListPage(backStack, viewModel)
        }
        entry<Route.Note>(metadata = ListDetailPage()) {
            NotePage(backStack, viewModel, it.id)
        }
    }
}