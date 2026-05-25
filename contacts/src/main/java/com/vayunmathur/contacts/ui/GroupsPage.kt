package com.vayunmathur.contacts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsPage(viewModel: ContactViewModel, backStack: NavBackStack<Route>, expandGroupId: Long? = null) {
    val groups by viewModel.groups.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    val expandedGroups = remember { 
        val list = mutableStateListOf<Long>()
        expandGroupId?.let { list.add(it) }
        list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups)) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                IconAdd()
            }
        },
        bottomBar = {
            ContactsBottomNavBar(backStack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groups, key = { it.id }) { group ->
                val contactsInGroup by viewModel.getContactsForGroup(group.id).collectAsState(initial = emptyList())
                val isExpanded = group.id in expandedGroups

                Column {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Text("${contactsInGroup.size} contacts")
                        },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(
                                        color = getAvatarColor(group.id),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_group_24),
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteGroup(group.id) }) {
                                IconDelete()
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (isExpanded) {
                                    expandedGroups.remove(group.id)
                                } else {
                                    expandedGroups.add(group.id)
                                }
                            },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    if (isExpanded) {
                        contactsInGroup.forEach { contact ->
                            ContactItem(
                                contact = contact,
                                isSelected = false,
                                showAccountLabels = false,
                                viewModel = viewModel,
                                onClick = {
                                    backStack.add(Route.ContactDetail(contact.id))
                                },
                                modifier = Modifier.padding(start = 32.dp, top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_group)) },
            text = {
                TextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    placeholder = { Text(stringResource(R.string.enter_group_name)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newGroupName.isNotBlank()) {
                        viewModel.addGroup(newGroupName)
                        newGroupName = ""
                        showAddDialog = false
                    }
                }) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
