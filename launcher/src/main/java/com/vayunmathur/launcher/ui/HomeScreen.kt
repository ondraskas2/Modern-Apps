package com.vayunmathur.launcher.ui

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.vayunmathur.launcher.AppInfo
import com.vayunmathur.launcher.LauncherViewModel
import com.vayunmathur.launcher.search.GroupedResults
import com.vayunmathur.launcher.search.SearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    onSearch = {},
                    expanded = isSearching,
                    onExpandedChange = viewModel::setSearching,
                    placeholder = { Text("Search apps, contacts, events…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (isSearching) {
                            IconButton(onClick = { viewModel.setSearching(false) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                    }
                )
            },
            expanded = isSearching,
            onExpandedChange = viewModel::setSearching,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isSearching) 0.dp else 16.dp)
        ) {
            SearchResultsList(searchResults) { result ->
                when (result) {
                    is SearchResult.App -> {
                        context.packageManager.getLaunchIntentForPackage(result.packageName)?.let {
                            context.startActivity(it)
                        }
                    }
                    is SearchResult.Contact -> {
                        val uri = ContactsContract.Contacts.getLookupUri(
                            result.contactId.toLongOrNull() ?: 0L,
                            result.lookupKey
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    is SearchResult.CalendarEvent -> {
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, result.eventId)
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        AppGrid(apps) { app ->
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                context.startActivity(it)
            }
        }
    }
}

@Composable
fun AppGrid(apps: List<AppInfo>, onAppClick: (AppInfo) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(80.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(apps, key = { it.packageName }) { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onAppClick(app) }
                    .padding(4.dp)
            ) {
                Image(
                    bitmap = remember(app.icon) {
                        val w = app.icon.intrinsicWidth.coerceAtLeast(1)
                        val h = app.icon.intrinsicHeight.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        app.icon.setBounds(0, 0, w, h)
                        app.icon.draw(canvas)
                        bmp.asImageBitmap()
                    },
                    contentDescription = app.name,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SearchResultsList(results: GroupedResults, onResultClick: (SearchResult) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        if (results.apps.isNotEmpty()) {
            item {
                Text(
                    "Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results.apps) { app ->
                ListItem(
                    headlineContent = { Text(app.name) },
                    supportingContent = { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { onResultClick(app) }
                )
            }
        }
        if (results.contacts.isNotEmpty()) {
            item {
                if (results.apps.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results.contacts) { contact ->
                ListItem(
                    headlineContent = { Text(contact.name) },
                    supportingContent = {
                        if (contact.phones.isNotBlank()) Text(contact.phones, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.clickable { onResultClick(contact) }
                )
            }
        }
        if (results.events.isNotEmpty()) {
            item {
                if (results.apps.isNotEmpty() || results.contacts.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Events",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results.events) { event ->
                ListItem(
                    headlineContent = { Text(event.title) },
                    supportingContent = {
                        val subtitle = listOfNotNull(
                            event.date.takeIf { it.isNotBlank() },
                            event.location.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    modifier = Modifier.clickable { onResultClick(event) }
                )
            }
        }
        if (results.apps.isEmpty() && results.contacts.isEmpty() && results.events.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No results", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
