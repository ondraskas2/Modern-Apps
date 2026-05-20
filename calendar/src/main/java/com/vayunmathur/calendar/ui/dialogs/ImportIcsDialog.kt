package com.vayunmathur.calendar.ui.dialogs

import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.ui.EventCard
import com.vayunmathur.calendar.ui.parseICSFile
import com.vayunmathur.calendar.util.CalendarViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ImportIcsDialog(
    viewModel: CalendarViewModel,
    uris: List<Uri>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var calendars by remember { mutableStateOf<List<Calendar>>(emptyList()) }
    var selectedCalendar by remember { mutableStateOf<Calendar?>(null) }
    var showDropdown by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddCalendar by remember { mutableStateOf(false) }
    var newCalName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uris, showAddCalendar) {
        if (!showAddCalendar) {
            withContext(Dispatchers.IO) {
                if (events.isEmpty()) {
                    val allEvents = mutableListOf<Event>()
                    uris.forEach { uri ->
                        try {
                            context.contentResolver.openInputStream(uri)?.use { iS ->
                                allEvents.addAll(parseICSFile(iS))
                            }
                        } catch (e: Exception) {
                            Log.e("ImportIcsDialog", "Error parsing ICS file: $uri", e)
                        }
                    }
                    events = allEvents
                    if (allEvents.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            onDismiss()
                        }
                    }
                }
                calendars = Calendar.getAllCalendars(context)
                isLoading = false
            }
        }
    }

    if (showAddCalendar) {
        AlertDialog(
            onDismissRequest = { showAddCalendar = false },
            title = { Text("New Local Calendar") },
            text = {
                TextField(
                    value = newCalName,
                    onValueChange = { newCalName = it },
                    label = { Text("Calendar Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newCalName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            viewModel.createLocalCalendar("Offline Calendar", newCalName, 0xFF2196F3.toInt(), true, CalendarContract.Calendars.CAL_ACCESS_EDITOR)
                            withContext(Dispatchers.Main) {
                                showAddCalendar = false
                                newCalName = ""
                            }
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCalendar = false }) { Text("Cancel") }
            }
        )
    }

    if (isLoading) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            title = { Text("Loading events...") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import Events") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("Select calendar to import to:")
                    Spacer(Modifier.height(8.dp))
                    
                    Box {
                        val editable = calendars.filter(Calendar::canModify)
                        val grouped = editable.groupBy { it.accountName.ifEmpty { "(Local)" } }
                        
                        ListItem(
                            headlineContent = { Text(selectedCalendar?.displayName ?: "Select Calendar") },
                            leadingContent = {
                                selectedCalendar?.color?.let { Box(Modifier.size(24.dp).background(Color(it), RectangleShape)) }
                            },
                            trailingContent = {
                                Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null)
                            },
                            modifier = Modifier.clickable { showDropdown = true }
                        )
                        
                        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
                            grouped.forEach { (account, cals) ->
                                DropdownMenuItem(text = { Text(account) }, onClick = {}, enabled = false)
                                cals.forEach { cal ->
                                    DropdownMenuItem(
                                        text = { Text(cal.displayName) },
                                        leadingIcon = { Box(Modifier.size(16.dp).background(Color(cal.color), RectangleShape)) },
                                        onClick = {
                                            selectedCalendar = cal
                                            showDropdown = false
                                        }
                                    )
                                }
                            }
                            Divider()
                            DropdownMenuItem(
                                text = { Text("+ Create new calendar") },
                                onClick = {
                                    showAddCalendar = true
                                    showDropdown = false
                                }
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(events) { event ->
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
                        scope.launch(Dispatchers.IO) {
                            try {
                                val valuesList = events.map { event ->
                                    ContentValues().apply {
                                        put(CalendarContract.Events.TITLE, event.title)
                                        put(CalendarContract.Events.DESCRIPTION, event.description)
                                        put(CalendarContract.Events.EVENT_LOCATION, event.location)
                                        put(CalendarContract.Events.CALENDAR_ID, calId)
                                        val startDate = event.startDateTimeDisplay.date
                                        val startTime = event.startDateTimeDisplay.time
                                        val endDate = event.endDateTimeDisplay.date
                                        val endTime = event.endDateTimeDisplay.time
                                        val tz = if (event.allDay) "UTC" else event.timezone
                                        val dtstart = startDate.atTime(startTime).toInstant(TimeZone.of(tz))
                                            .toEpochMilliseconds()
                                        val dtendActual = endDate.atTime(endTime).toInstant(TimeZone.of(tz))
                                            .toEpochMilliseconds()
                                        put(CalendarContract.Events.DTSTART, dtstart)
                                        if (event.rrule != null) {
                                            put(CalendarContract.Events.DTEND, null as Long?)
                                            var duration = (dtendActual - dtstart).milliseconds
                                            if (event.allDay) duration += 1.days
                                            put(CalendarContract.Events.DURATION, duration.toIsoString())
                                            put(CalendarContract.Events.RRULE, event.rrule.asString(startDate, TimeZone.of(tz)))
                                        } else {
                                            put(CalendarContract.Events.DTEND, dtendActual)
                                            put(CalendarContract.Events.DURATION, null as String?)
                                            put(CalendarContract.Events.RRULE, null as String?)
                                        }
                                        put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                                        put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                                    }
                                }
                                context.contentResolver.bulkInsert(CalendarContract.Events.CONTENT_URI, valuesList.toTypedArray())
                                withContext(Dispatchers.Main) {
                                    onDismiss()
                                }
                            } catch (e: Exception) {
                                Log.e("ImportIcsDialog", "Error importing events", e)
                            }
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}
