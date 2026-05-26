package com.vayunmathur.email

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var dataStore: DataStoreUtils

    // Configuration Constants
    private val clientId = "827025129169-1ihnv9r1a8nd1i3qjs98tkvluo4vjbhe.apps.googleusercontent.com"
    private val redirectUri = "com.googleusercontent.apps.827025129169-1ihnv9r1a8nd1i3qjs98tkvluo4vjbhe:/oauth2redirect"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStore = DataStoreUtils.getInstance(this)
        
        // Restore session from DataStore
        TokenState.accessToken = dataStore.getString("access_token")
        TokenState.userEmail = dataStore.getString("user_email")
        
        if (TokenState.accessToken != null) {
            EmailSyncWorker.schedulePeriodicSync(this)
        }

        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                MainContent(
                    onGoogleLogin = { startGoogleLogin() },
                    onLogout = { logout() }
                )
            }
        }
    }

    private fun logout() {
        scope.launch {
            dataStore.setString("access_token", "")
            dataStore.setString("user_email", "")
            TokenState.accessToken = null
            TokenState.userEmail = null
            EmailSyncWorker.cancelSync(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        val expectedScheme = "com.googleusercontent.apps.827025129169-1ihnv9r1a8nd1i3qjs98tkvluo4vjbhe"
        if (data != null && data.scheme == expectedScheme && data.path == "/oauth2redirect") {
            val code = data.getQueryParameter("code")
            if (code != null) {
                exchangeCodeForToken(code)
            }
        }
    }

    private fun startGoogleLogin() {
        val verifier = generateCodeVerifier()
        TokenState.codeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        val authUri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "https://mail.google.com/ email profile")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .build()

        startActivity(Intent(Intent.ACTION_VIEW, authUri))
    }

    private fun exchangeCodeForToken(code: String) {
        val verifier = TokenState.codeVerifier ?: return

        scope.launch {
            try {
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }.use { client ->
                    val httpResponse = client.submitForm(
                        url = "https://oauth2.googleapis.com/token",
                        formParameters = parameters {
                            append("client_id", clientId)
                            append("code", code)
                            append("code_verifier", verifier)
                            append("grant_type", "authorization_code")
                            append("redirect_uri", redirectUri)
                        }
                    )

                    if (httpResponse.status.isSuccess()) {
                        val response: TokenResponse = httpResponse.body()
                        
                        // Fetch user info to get email address
                        val userInfo: UserInfo = client.get("https://www.googleapis.com/oauth2/v3/userinfo") {
                            bearerAuth(response.accessToken)
                        }.body()

                        TokenState.userEmail = userInfo.email
                        TokenState.accessToken = response.accessToken
                        
                        // Persist to DataStore
                        dataStore.setString("access_token", response.accessToken)
                        dataStore.setString("user_email", userInfo.email)
                        
                        EmailSyncWorker.schedulePeriodicSync(this@MainActivity)
                        EmailSyncWorker.runOneOffSync(this@MainActivity)
                    } else {
                        val errorText = httpResponse.bodyAsText()
                        android.util.Log.e("OAuthError", "Failed to exchange code: $errorText")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val sr = SecureRandom()
        val code = ByteArray(32)
        sr.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trim()
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        val digest = md.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trim()
    }
}

object TokenState {
    var accessToken by mutableStateOf<String?>(null)
    var userEmail by mutableStateOf<String?>(null)
    var codeVerifier: String? = null
}

@Serializable
data class TokenResponse(
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Int,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null,
    @kotlinx.serialization.SerialName("scope") val scope: String,
    @kotlinx.serialization.SerialName("token_type") val tokenType: String
)

@Serializable
data class UserInfo(
    val email: String,
    val name: String? = null,
    val picture: String? = null
)

@Composable
fun MainContent(onGoogleLogin: () -> Unit, onLogout: () -> Unit) {
    if (TokenState.accessToken == null || TokenState.userEmail == null) {
        LoginScreen(onGoogleLogin)
    } else {
        EmailApp(onLogout = onLogout)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onGoogleLogin: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Email") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome to Email",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            Button(
                onClick = onGoogleLogin,
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Sign in with Google")
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    object MessageList : Route
    @Serializable
    data class MessageDetail(val folderName: String, val messageId: Long) : Route
}

@Composable
fun EmailApp(viewModel: EmailViewModel = viewModel(), onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val folders by viewModel.folders.collectAsState(emptyList())
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    
    val backStack = rememberNavBackStack<Route>(Route.MessageList)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Folders", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                FolderList(folders, selectedFolder) { folderName ->
                    viewModel.selectFolder(folderName)
                    scope.launch { drawerState.close() }
                }
                Spacer(Modifier.weight(1f))
                NavigationDrawerItem(
                    label = { Text("Logout") },
                    selected = false,
                    onClick = onLogout,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        MainNavigation(backStack) {
            entry<Route.MessageList>(metadata = ListPage()) {
                MessageListScreen(
                    viewModel = viewModel,
                    onMessageClick = { msg ->
                        backStack.add(Route.MessageDetail(selectedFolder, msg.id))
                    },
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            entry<Route.MessageDetail>(metadata = ListDetailPage()) { route ->
                MessageDetailScreen(
                    viewModel = viewModel,
                    folderName = route.folderName,
                    messageId = route.messageId,
                    onBack = { backStack.pop() }
                )
            }
        }
    }
}

@Composable
fun FolderList(folders: List<EmailFolder>, selectedFolder: String, onSelect: (String) -> Unit) {
    val folderTree = remember(folders) { buildFolderTree(folders) }
    
    LazyColumn {
        folderTree.forEach { root ->
            renderFolderTree(root, 0, selectedFolder, onSelect)
        }
    }
}

data class FolderNode(val folder: EmailFolder, val children: List<FolderNode>)

fun buildFolderTree(folders: List<EmailFolder>): List<FolderNode> {
    val folderMap = folders.associateBy { it.fullName }
    val childrenMap = folders.groupBy { it.parentFullName }
    
    fun buildNode(folder: EmailFolder): FolderNode {
        return FolderNode(
            folder = folder,
            children = childrenMap[folder.fullName]?.map { buildNode(it) } ?: emptyList()
        )
    }
    
    return folders.filter { it.parentFullName == null }.map { buildNode(it) }
}

fun androidx.compose.foundation.lazy.LazyListScope.renderFolderTree(
    node: FolderNode, 
    depth: Int, 
    selectedFolder: String, 
    onSelect: (String) -> Unit
) {
    item {
        NavigationDrawerItem(
            label = { Text(node.folder.name) },
            selected = node.folder.fullName == selectedFolder,
            onClick = { onSelect(node.folder.fullName) },
            modifier = Modifier
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .padding(start = (depth * 16).dp)
        )
    }
    node.children.forEach { child ->
        renderFolderTree(child, depth + 1, selectedFolder, onSelect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    viewModel: EmailViewModel,
    onMessageClick: (EmailMessage) -> Unit,
    onOpenDrawer: () -> Unit
) {
    val messages by viewModel.messages.collectAsState(emptyList())
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            if (isSearching) {
                TopAppBar(
                    title = {
                        CommonSearchBar(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            padding = PaddingValues(0.dp)
                        )
                    },
                    navigationIcon = {
                        IconNavigation { 
                            isSearching = false
                            viewModel.setSearchQuery("")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(selectedFolder) },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            IconMenu()
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh(context) }) {
                            IconRestore()
                        }
                        IconButton(onClick = { isSearching = true }) {
                            IconSearch()
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (messages.isEmpty() && searchQuery.isEmpty()) {
                Text(
                    text = "No messages found. Try refreshing.",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        Card(
                            onClick = { onMessageClick(message) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = message.from, style = MaterialTheme.typography.labelMedium)
                                Text(text = message.subject, style = MaterialTheme.typography.titleMedium)
                                Text(text = message.date, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    viewModel: EmailViewModel,
    folderName: String,
    messageId: Long,
    onBack: () -> Unit
) {
    var message by remember { mutableStateOf<EmailMessage?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(messageId) {
        message = viewModel.getMessage(folderName, messageId)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Detail") },
                navigationIcon = {
                    IconNavigation(onBack)
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                message?.let { msg ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(text = "From: ${msg.from}", style = MaterialTheme.typography.titleMedium)
                        Text(text = "Subject: ${msg.subject}", style = MaterialTheme.typography.titleLarge)
                        Text(text = "Date: ${msg.date}", style = MaterialTheme.typography.labelMedium)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Text(text = msg.body ?: "(No Content Offline)", style = MaterialTheme.typography.bodyLarge)
                    }
                } ?: Text("Message not found offline.", modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
