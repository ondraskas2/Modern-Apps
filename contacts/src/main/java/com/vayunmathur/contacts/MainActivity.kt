package com.vayunmathur.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.ui.*
import com.vayunmathur.contacts.ui.dialogs.*
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {
    private val importUris = mutableStateOf<List<Uri>>(emptyList())
    private val externalRoute = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            var hasPermissions by remember { mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) }
            DynamicTheme {
                if (!hasPermissions) {
                    NoPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    val viewModel: ContactViewModel = viewModel()
                    
                    val uris by importUris
                    if (uris.isNotEmpty()) {
                        ImportVcfDialog(viewModel, uris) { importUris.value = emptyList() }
                    }

                    // If the app was launched with ACTION_PICK/GET_CONTENT, forward to the picker flow.
                    if (intent.action == Intent.ACTION_PICK || intent.action == Intent.ACTION_GET_CONTENT) {
                        var type = intent.type
                        if (intent.data?.toString()?.contains("phones") == true) {
                            type = CDKPhone.CONTENT_ITEM_TYPE
                        }
                        val contacts by viewModel.contacts.collectAsState()
                        ContactListPick(type, contacts) {
                            val resultIntent = Intent().apply {
                                data = it
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        }
                    } else {
                        val route by externalRoute
                        Box(Modifier.fillMaxSize().onFileDrop { importUris.value = it }) {
                            Navigation(viewModel, route)
                        }
                    }
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
            val type = it.type ?: ""
            val isVcf = type.contains("vcard") || type.contains("vcf") || it.data?.path?.endsWith(".vcf", ignoreCase = true) == true
            
            if (isVcf) {
                val uris = IntentHelper.getUrisFromIntent(it)
                if (uris.isNotEmpty()) {
                    importUris.value = uris
                }
            }

            if (it.action == Intent.ACTION_VIEW || it.action == Intent.ACTION_EDIT || it.action == Intent.ACTION_INSERT) {
                externalRoute.value = when (it.action) {
                    Intent.ACTION_INSERT -> {
                        Route.EditContact(
                            contactId = null,
                            name = it.getStringExtra(ContactsContract.Intents.Insert.NAME),
                            phone = it.getStringExtra(ContactsContract.Intents.Insert.PHONE),
                            email = it.getStringExtra(ContactsContract.Intents.Insert.EMAIL),
                            company = it.getStringExtra(ContactsContract.Intents.Insert.COMPANY),
                            jobTitle = it.getStringExtra(ContactsContract.Intents.Insert.JOB_TITLE),
                            notes = it.getStringExtra(ContactsContract.Intents.Insert.NOTES)
                        )
                    }
                    Intent.ACTION_EDIT -> {
                        val contactId = it.data?.lastPathSegment?.toLongOrNull()
                        Route.EditContact(
                            contactId = contactId,
                            name = it.getStringExtra(ContactsContract.Intents.Insert.NAME),
                            phone = it.getStringExtra(ContactsContract.Intents.Insert.PHONE),
                            email = it.getStringExtra(ContactsContract.Intents.Insert.EMAIL)
                        )
                    }
                    Intent.ACTION_VIEW -> {
                        val lastSegment = it.data?.lastPathSegment
                        val path = it.data?.path ?: ""
                        val type = it.type
                        when {
                            path.contains("/groups") || type?.contains("group") == true -> {
                                lastSegment?.toLongOrNull()?.let { Route.GroupsList(it) }
                            }
                            else -> {
                                lastSegment?.toLongOrNull()?.let { Route.ContactDetail(it) }
                            }
                        }
                    }
                    else -> null
                }
            }
        }
    }
}

@Composable
fun NoPermissionsScreen(permissions: Array<String>, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            androidx.compose.material3.Button(
                {
                    permissionRequestor.launch(permissions)
                }, Modifier.align(Alignment.Center)
            ) {
                Text(text = stringResource(R.string.grant_contacts_permission))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Navigation(viewModel: ContactViewModel, initialRoute: Route? = null) {
    val backStack = rememberNavBackStack<Route>(listOfNotNull(Route.ContactsList, initialRoute).distinct())

    LaunchedEffect(initialRoute) {
        if (initialRoute != null && backStack.last() != initialRoute) {
            backStack.add(initialRoute)
        }
    }

    val isCalendarSyncEnabled by viewModel.isCalendarSyncEnabled.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(isCalendarSyncEnabled) {
        if (isCalendarSyncEnabled) {
            com.vayunmathur.contacts.util.CalendarWorker.schedule(context)
        }
    }

    MainNavigation(backStack) {
        entry<Route.ContactsList>(metadata = ListPage {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(stringResource(R.string.select_contact_hint))
            }
        }) {
            ContactList(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = { contact ->
                    if (backStack.last() is Route.ContactDetail || backStack.last() is Route.EditContact) {
                        backStack.setLast(Route.ContactDetail(contact.id))
                    } else {
                        backStack.add(Route.ContactDetail(contact.id))
                    }
                },
                onAddContactClick = {
                    if (backStack.last() is Route.ContactDetail) {
                        backStack.pop()
                    }
                    backStack.add(Route.EditContact(null))
                }
            )
        }

        entry<Route.GroupsList>(metadata = ListPage {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(stringResource(R.string.select_group_hint))
            }
        }) { key ->
            GroupsPage(viewModel, backStack, key.expandGroupId)
        }

        entry<Route.ContactDetail>(metadata = ListDetailPage()) { key ->
            ContactDetailsPage(
                viewModel = viewModel,
                contactId = key.contactId,
                onBack = { backStack.pop() },
                onEdit = { id -> backStack.add(Route.EditContact(id)) },
                onDelete = {
                    // Show the delete confirmation dialog using the contact id and name
                    val contact = viewModel.getContact(key.contactId)
                    backStack.add(Route.EventDeleteConfirmDialog(key.contactId, contact?.name?.value))
                },
                showBackButton = true
            )
        }
        entry<Route.EditContact>(metadata = ListDetailPage()) { key ->
            EditContactPage(backStack, viewModel, key)
        }

        entry<Route.Settings>(metadata = ListDetailPage()) {
            SettingsPage(viewModel, backStack)
        }

        entry<Route.AddAccountDialog>(metadata = DialogPage()) {
            AddAccountDialog(viewModel) { backStack.pop() }
        }

        entry<Route.EventDatePickerDialog>(metadata = DialogPage()) { key ->
            EventDatePickerDialog(key.id, key.initialDate) { backStack.pop() }
        }

        entry<Route.EventDeleteConfirmDialog>(metadata = DialogPage()) { key ->
            EventDeleteConfirmDialog(key.contactId, key.contactName, viewModel, onConfirm = {
                // After confirming deletion, pop the dialog and the detail page
                backStack.pop()
                backStack.pop()
            }, onDismiss = {
                // Only close the dialog
                backStack.pop()
            })
        }

        entry<Route.AddToGroupDialog>(metadata = DialogPage()) { key ->
            AddToGroupDialog(viewModel, key.contactIds) { backStack.pop() }
        }
    }
}

sealed interface Route: NavKey {
    @Serializable
    object ContactsList : Route

    @Serializable
    data class GroupsList(val expandGroupId: Long? = null) : Route

    @Serializable
    data class AddToGroupDialog(val contactIds: List<Long>) : Route

    @Serializable
    data class ContactDetail(val contactId: Long) : Route

    @Serializable
    data class EditContact(
        val contactId: Long?,
        val name: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val company: String? = null,
        val jobTitle: String? = null,
        val notes: String? = null
    ) : Route

    @Serializable
    data class EventDatePickerDialog(val id: String, val initialDate: LocalDate?): Route

    @Serializable
    data class EventDeleteConfirmDialog(val contactId: Long, val contactName: String?): Route

    @Serializable
    object Settings : Route

    @Serializable
    object AddAccountDialog : Route
}
