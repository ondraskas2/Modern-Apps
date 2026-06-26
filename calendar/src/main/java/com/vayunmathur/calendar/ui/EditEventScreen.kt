package com.vayunmathur.calendar.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.ResultEffect
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

// Result keys for the date/time pickers
private const val KEY_START_DATE = "EditEvent.startDate"
private const val KEY_END_DATE = "EditEvent.endDate"
private const val KEY_START_TIME = "EditEvent.startTime"
private const val KEY_END_TIME = "EditEvent.endTime"
private const val KEY_RECURRENCE = "EditEvent.recurrence"
private const val KEY_CALENDAR = "EditEvent.calendar"
private const val KEY_TIMEZONE = "EditEvent.timezone"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(viewModel: CalendarViewModel, editRoute: Route.EditEvent, backStack: NavBackStack<Route>) {
    val eventId = editRoute.id
    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val event = events.find { it.id == eventId }

    val znow = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = znow.date
    val now = znow.time

    var title by remember { mutableStateOf(event?.title ?: editRoute.title ?: "") }
    var description by remember { mutableStateOf(TextFieldValue(event?.description ?: editRoute.description ?: "")) }
    var descriptionFocused by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(event?.location ?: editRoute.location ?: "") }
    // default to the event's calendar if editing; otherwise prefer the first editable calendar
    var selectedCalendar by remember { mutableLongStateOf(event?.calendarID ?: (calendars.firstOrNull { it.canModify }?.id ?: calendars.firstOrNull()?.id ?: -1L)) }
    // If calendars load/refresh after composition, ensure the default remains an editable calendar when creating a new event
    LaunchedEffect(calendars) {
        if (event == null) {
            val current = selectedCalendar
            val currentIsEditable = calendars.any { it.id == current && it.canModify }
            if (!currentIsEditable) {
                val editable = calendars.firstOrNull { it.canModify } ?: calendars.firstOrNull()
                if (editable != null) selectedCalendar = editable.id
            }
        }
    }

    val initialBeginLdt = editRoute.beginTime?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
    val initialEndLdt = editRoute.endTime?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
        ?: initialBeginLdt?.let {
            val tz = TimeZone.currentSystemDefault()
            it.toInstant(tz).plus(1.hours).toLocalDateTime(tz)
        }

    var allDay by remember { mutableStateOf(event?.allDay ?: editRoute.allDay ?: false) }
    var startDate by remember { mutableStateOf(event?.startDateTimeDisplay?.date ?: initialBeginLdt?.date ?: today) }
    var endDate by remember { mutableStateOf(event?.endDateTimeDisplay?.date ?: initialEndLdt?.date ?: startDate) }
    var startTime by remember { mutableStateOf(event?.startDateTimeDisplay?.time ?: initialBeginLdt?.time ?: now) }
    var endTime by remember { mutableStateOf(event?.endDateTimeDisplay?.time ?: initialEndLdt?.time ?: startTime) }
    var timezone by remember { mutableStateOf(event?.timezone ?: TimeZone.currentSystemDefault().id) }
    var rruleObj by remember { mutableStateOf(event?.rrule) }
    val rruleString by remember { derivedStateOf {rruleObj?.toString() ?: ""} }
    var reminders by remember { mutableStateOf(event?.reminders ?: emptyList()) }

    // Shift the end date/time to preserve the current event duration when the start moves.
    fun applyStartChange(newStartDate: LocalDate, newStartTime: LocalTime) {
        val tz = TimeZone.of(timezone)
        val oldStart = startDate.atTime(startTime).toInstant(tz)
        val oldEnd = endDate.atTime(endTime).toInstant(tz)
        var dur = oldEnd - oldStart
        if (dur.isNegative()) dur = Duration.ZERO
        startDate = newStartDate
        startTime = newStartTime
        val newEndLdt = (newStartDate.atTime(newStartTime).toInstant(tz) + dur).toLocalDateTime(tz)
        endDate = newEndLdt.date
        endTime = newEndLdt.time
    }

    // Collect results from pickers
    ResultEffect<LocalDate>(KEY_START_DATE) { selected ->
        applyStartChange(selected, startTime)
    }

    ResultEffect<LocalDate>(KEY_END_DATE) { selected ->
        // ensure end date is not before start date
        endDate = maxOf(startDate, selected)
    }

    ResultEffect<LocalTime>(KEY_START_TIME) { selected ->
        applyStartChange(startDate, selected)
    }

    ResultEffect<LocalTime>(KEY_END_TIME) { selected ->
        // ensure end time is not before start time when on same date
        endTime = if (endDate == startDate) {
            maxOf(selected, startTime)
        } else {
            selected
        }
    }

    // Recurrence dialog result: receives an RRULE string or empty string
    ResultEffect<RRule>(KEY_RECURRENCE) { res ->
        rruleObj = res
    }

    // Result key for calendar picker
    // open dialog via navigation and handle result
    ResultEffect<Long>(KEY_CALENDAR) { calId ->
        selectedCalendar = calId
    }

    // Timezone selector (navigation dialog) - open via Nav route and handle result
    ResultEffect<String>(KEY_TIMEZONE) { z -> timezone = z }

    Scaffold(topBar = {
        TopAppBar({}, navigationIcon = {
            IconNavigation(backStack)
        })
    }, bottomBar = {
        if (descriptionFocused) {
            com.vayunmathur.library.ui.MarkdownFormatToolbar(
                value = description,
                onValueChange = { description = it },
            )
        }
    }, floatingActionButton = {
        FloatingActionButton(onClick = {
            val buildTz = if (allDay) TimeZone.UTC else TimeZone.of(timezone)
            val newEvent = Event(
                id = eventId,
                calendarID = selectedCalendar,
                title = title,
                description = description.text,
                location = location,
                color = event?.color,
                start = startDate.atTime(startTime).toInstant(buildTz).toEpochMilliseconds(),
                end = endDate.atTime(endTime).toInstant(buildTz).toEpochMilliseconds(),
                timezone = if (allDay) "UTC" else timezone,
                allDay = allDay,
                rrule = rruleObj,
                exdate = event?.exdate ?: emptyList(),
                reminders = reminders,
            )
            viewModel.upsertEvent(eventId, newEvent.toContentValues(selectedCalendar), reminders)
            backStack.pop()
        }) {
            IconSave()
        }
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues).verticalScroll(rememberScrollState())) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text(stringResource(R.string.label_title)) })

            // Calendar selector: moved above the datetime section — only when creating a new event
            if (eventId == null) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color(calendars.find { it.id == selectedCalendar }?.color ?: 0))) },
                    { Text(calendars.find { it.id == selectedCalendar }?.displayName ?: stringResource(R.string.select_calendar), Modifier.clickable { backStack.add(Route.EditEvent.CalendarPickerDialog(KEY_CALENDAR)) }) },
                    {}
                )
            }

            Text(
                text = stringResource(R.string.label_description),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            com.vayunmathur.library.ui.MarkdownEditor(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.label_description),
                onFocusChanged = { descriptionFocused = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .heightIn(min = 96.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Item(
                { Icon(painterResource(R.drawable.nest_clock_farsight_analog_24px), null) },
                {Text(stringResource(R.string.all_day))},
                { Switch(allDay, { allDay = it }) }
            )

            // Recurrence selector
            Item(
                { /* icon placeholder */ },
                { Text(if (rruleObj == null) stringResource(R.string.does_not_repeat) else rruleString.ifBlank { stringResource(R.string.repeats) }, Modifier.clickable {
                    // pass initial RecurrenceParams based on existing rrule
                    val initial = RecurrenceParams.fromRRule(rruleObj)
                    backStack.add(Route.EditEvent.RecurrenceDialog(KEY_RECURRENCE, startDate, initial))
                }) },
                { if (rruleObj != null) Text(stringResource(R.string.remove), Modifier.clickable {
                    rruleObj = null
                }) }
            )

            Item(
                {},
                { Text(startDate.format(dateFormat), Modifier.clickable {
                    // open date picker dialog
                    backStack.add(Route.EditEvent.DatePickerDialog(KEY_START_DATE, startDate))
                }) },
                { if(!allDay) Text(startTime.format(if(DateFormat.is24HourFormat(context)) timeFormat24 else timeFormat12), Modifier.clickable {
                    // open time picker dialog
                    // no min time for start
                    backStack.add(Route.EditEvent.TimePickerDialog(KEY_START_TIME, startTime, null))
                }) }
            )
            Item(
                {},
                { Text(endDate.format(dateFormat), Modifier.clickable {
                    // when opening end date, prevent selecting a date before startDate
                    backStack.add(Route.EditEvent.DatePickerDialog(KEY_END_DATE, endDate, startDate))
                }) },
                { if(!allDay) Text(endTime.format(if(DateFormat.is24HourFormat(context)) timeFormat24 else timeFormat12), Modifier.clickable{
                    // when opening end time, supply minTime if endDate equals startDate
                    val minTime = if (endDate == startDate) startTime else null
                    backStack.add(Route.EditEvent.TimePickerDialog(KEY_END_TIME, endTime, minTime))
                }) }
            )

            if (!allDay) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color.Transparent)) { Icon(painterResource(R.drawable.globe_24px), null) } },
                    { Text(timezone, Modifier.clickable { backStack.add(Route.EditEvent.TimezonePickerDialog(KEY_TIMEZONE)) }) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // Reminders
            reminders.forEach { minutes ->
                Item(
                    { Icon(painterResource(R.drawable.nest_clock_farsight_analog_24px), null) },
                    { Text(reminderLabel(minutes)) },
                    { Text(stringResource(R.string.remove), Modifier.clickable { reminders = reminders - minutes }) },
                )
            }
            var addReminderExpanded by remember { mutableStateOf(false) }
            val available = REMINDER_PRESETS.filter { it !in reminders }
            if (available.isNotEmpty()) {
                Item(
                    { Icon(painterResource(R.drawable.nest_clock_farsight_analog_24px), null) },
                    {
                        Box {
                            Text(stringResource(R.string.add_reminder), Modifier.clickable { addReminderExpanded = true })
                            DropdownMenu(addReminderExpanded, { addReminderExpanded = false }) {
                                available.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(reminderLabel(m)) },
                                        onClick = {
                                            addReminderExpanded = false
                                            reminders = (reminders + m).sorted()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text(stringResource(R.string.label_location)) })
        }
    }
}

@Composable
fun Item(icon: @Composable () -> Unit = {}, left: @Composable () -> Unit, right: @Composable () -> Unit = {}) {
    Row(Modifier.padding(8.dp).padding(horizontal = 8.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            Box(Modifier.size(24.dp)) {
                icon()
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f)) {
                left()
            }
            right()
        }
    }
}

val dateFormat = LocalDate.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    chars(", ")
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    chars(" ")
    day(Padding.NONE)
    chars(", ")
    year(Padding.NONE)
}

val timeFormat12 = LocalTime.Format {
    amPmHour(Padding.NONE)
    chars(":")
    minute()
    chars(" ")
    amPmMarker("AM", "PM")
}

val timeFormat24 = LocalTime.Format {
    hour(Padding.ZERO)
    chars(":")
    minute()
}

/** Common reminder offsets, in minutes before the event start. */
val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

fun reminderLabel(minutes: Int): String = when {
    minutes <= 0 -> "At time of event"
    minutes % 1440 == 0 -> "${minutes / 1440} day${if (minutes / 1440 > 1) "s" else ""} before"
    minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes / 60 > 1) "s" else ""} before"
    else -> "$minutes minutes before"
}