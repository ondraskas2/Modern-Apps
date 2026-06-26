package com.vayunmathur.calendar.ui.dialogs

import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.ui.CalendarSelectorDropdown
import com.vayunmathur.calendar.ui.EventCard
import com.vayunmathur.calendar.util.CalendarViewModel

@Composable
fun ImportIcsDialog(
    viewModel: CalendarViewModel,
    uris: List<Uri>,
    onDismiss: () -> Unit
) {
    val parsedEvents by viewModel.parsedIcsEvents.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    var selectedCalendar by remember { mutableStateOf<Calendar?>(null) }
    var showAddCalendar by remember { mutableStateOf(false) }
    var newCalName by remember { mutableStateOf("") }

    // Kick off parsing in the VM (Dispatchers.IO). Re-keying on `uris` ensures
    // we re-parse when the caller passes a new file set.
    LaunchedEffect(uris) {
        viewModel.parseIcsUris(uris)
    }

    // Clear VM-held parsed state when the dialog leaves composition.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearParsedIcs() }
    }

    // Auto-dismiss when parsing completes and yielded no events.
    LaunchedEffect(parsedEvents) {
        if (parsedEvents != null && parsedEvents!!.isEmpty() && uris.isNotEmpty()) {
            onDismiss()
        }
    }

    if (showAddCalendar) {
        AlertDialog(
            onDismissRequest = { showAddCalendar = false },
            title = { Text(stringResource(R.string.new_local_calendar_title)) },
            text = {
                TextField(
                    value = newCalName,
                    onValueChange = { newCalName = it },
                    label = { Text(stringResource(R.string.calendar_name_label)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newCalName.isNotBlank()) {
                        viewModel.createLocalCalendar(
                            "Offline Calendar",
                            newCalName,
                            0xFF2196F3.toInt(),
                            true,
                            CalendarContract.Calendars.CAL_ACCESS_EDITOR,
                        )
                        showAddCalendar = false
                        newCalName = ""
                    }
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddCalendar = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val events = parsedEvents
    if (events == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            title = { Text(stringResource(R.string.loading_events)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_events_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.select_calendar_to_import))
                    Spacer(Modifier.height(8.dp))

                    CalendarSelectorDropdown(
                        calendars = calendars,
                        selectedCalendar = selectedCalendar,
                        onSelect = { selectedCalendar = it },
                        onCreateNew = { showAddCalendar = true },
                    )

                    Spacer(Modifier.height(16.dp))
                    
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(events, key = { "${it.calendarID}|${it.start}|${it.title}" }) { event ->
                            EventCard(event = event)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedCalendar != null,
                    onClick = {
                        val calId = selectedCalendar?.id ?: return@TextButton
                        viewModel.importIcsEvents(events, calId, onDone = onDismiss)
                    }
                ) {
                    Text(stringResource(R.string.import_button))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
