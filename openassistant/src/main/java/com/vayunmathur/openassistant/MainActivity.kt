package com.vayunmathur.openassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.downloadservice.InitialDownloadChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.ui.LiteRTChatUi
import com.vayunmathur.openassistant.ui.SettingsPage
import com.vayunmathur.openassistant.util.AssistantViewModel
import com.vayunmathur.openassistant.util.AssistantViewModelFactory

class MainActivity : ComponentActivity() {

    companion object {
        lateinit var intentLauncher: IntentLauncher
    }

    private lateinit var viewModel: DatabaseViewModel
    private val assistantViewModel: AssistantViewModel by viewModels {
        AssistantViewModelFactory(application, viewModel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intentLauncher = IntentLauncher(this)

        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<AppDatabase>(migrations = AppDatabase.MIGRATIONS)
        viewModel = DatabaseViewModel(db, Conversation::class to db.conversationDao(), Message::class to db.messageDao(), Memory::class to db.memoryDao())

        setContent {
            DynamicTheme {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "gemma4-2b.litertlm", "Model"),
                )) {
                    // Touching the assistantViewModel triggers init, which pre-warms
                    // the inference service and runs the legacy model-file cleanup.
                    Navigation(viewModel, assistantViewModel)
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data class ConversationPage(val id: Long): Route
    @Serializable
    data object SettingsPage: Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel, assistantViewModel: AssistantViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ConversationPage(0))
    MainNavigation(backStack) {
        entry<Route.ConversationPage> {
            LiteRTChatUi(backStack, it.id, viewModel, assistantViewModel)
        }
        entry<Route.SettingsPage> {
            SettingsPage(backStack, viewModel)
        }
    }
}
