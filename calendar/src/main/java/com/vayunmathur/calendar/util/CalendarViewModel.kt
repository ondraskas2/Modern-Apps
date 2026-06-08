package com.vayunmathur.calendar.util
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.calendar.glance.CalendarGlanceWidget
import com.vayunmathur.calendar.ui.parseICSFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Calendar


import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    val dataStore = DataStoreUtils.getInstance(application)

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _calendars = MutableStateFlow<List<Calendar>>(emptyList())
    val calendars: StateFlow<List<Calendar>> = _calendars.asStateFlow()

    // map calendarId -> visible (whether to render events from that calendar)
    private val _calendarVisibility = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val calendarVisibility: StateFlow<Map<Long, Boolean>> = _calendarVisibility.asStateFlow()

    private val _lastViewedDate = MutableStateFlow<LocalDate?>(null)
    val lastViewedDate: StateFlow<LocalDate?> = _lastViewedDate.asStateFlow()

    enum class CalendarLayout(val shortName: String, val prettyName: String) {
        Agenda("A", "Agenda"),
        Day("D", "Day"),
        WorkWeek("W5", "Work Week"),
        FullWeek("W7", "Full Week"),
        Month("M", "Month"),
        WorkWeekSummary("W5S", "Work Week Summary"),
        FullWeekSummary("W7S", "Full Week Summary")
    }

    private val _currentLayout = MutableStateFlow<CalendarLayout>(
        dataStore.getString("default_calendar_layout")?.let { saved ->
            try { CalendarLayout.valueOf(saved) } catch (e: Exception) { CalendarLayout.FullWeek }
        } ?: CalendarLayout.FullWeek
    )
    val currentLayout: StateFlow<CalendarLayout> = _currentLayout.asStateFlow()

    fun setLayout(layout: CalendarLayout) {
        _currentLayout.value = layout
        viewModelScope.launch {
            dataStore.setString("default_calendar_layout", layout.name)
        }
    }

    fun setLastViewedDate(d: LocalDate?) {
        _lastViewedDate.value = d
    }

    // Currently selected/viewed date in the calendar UI. Always starts at today
    // so the user sees the current week on launch. Navigation within the app
    // updates this in-memory; persistence to DataStore is performed explicitly
    // via [setLastViewedDate] at navigation transitions.
    private val _selectedDate = MutableStateFlow<LocalDate>(
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    )
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    fun setSelectedDate(d: LocalDate) {
        _selectedDate.value = d
    }

    // Parsed-ICS state for the import dialog. null = not yet parsed (or cleared);
    // empty list = parsed and found nothing; non-empty = parsed events ready to import.
    private val _parsedIcsEvents = MutableStateFlow<List<Event>?>(null)
    val parsedIcsEvents: StateFlow<List<Event>?> = _parsedIcsEvents.asStateFlow()

    /** Parses every [uris] off the main thread and exposes the result via [parsedIcsEvents]. */
    fun parseIcsUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            _parsedIcsEvents.value = emptyList()
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val allEvents = mutableListOf<Event>()
            uris.forEach { uri ->
                try {
                    app.contentResolver.openInputStream(uri)?.use { iS ->
                        allEvents.addAll(parseICSFile(iS))
                    }
                } catch (e: Exception) {
                    Log.e("CalendarViewModel", "Error parsing ICS file: $uri", e)
                }
            }
            _parsedIcsEvents.value = allEvents
        }
    }

    /** Clears any parsed-ICS state held in the VM (called when the import dialog dismisses). */
    fun clearParsedIcs() {
        _parsedIcsEvents.value = null
    }

    /**
     * Bulk-inserts the previously parsed [events] into the calendar with id [calendarId].
     * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure).
     */
    fun importIcsEvents(
        events: List<Event>,
        calendarId: Long,
        onDone: () -> Unit = {},
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val valuesList = events.map { event ->
                        ContentValues().apply {
                            put(CalendarContract.Events.TITLE, event.title)
                            put(CalendarContract.Events.DESCRIPTION, event.description)
                            put(CalendarContract.Events.EVENT_LOCATION, event.location)
                            put(CalendarContract.Events.CALENDAR_ID, calendarId)
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
                                put(
                                    CalendarContract.Events.RRULE,
                                    event.rrule.asString(startDate, TimeZone.of(tz)),
                                )
                            } else {
                                put(CalendarContract.Events.DTEND, dtendActual)
                                put(CalendarContract.Events.DURATION, null as String?)
                                put(CalendarContract.Events.RRULE, null as String?)
                            }
                            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                            // Add EXDATE if present
                            if (event.exdate.isNotEmpty()) {
                                val exdateStr = event.exdate.joinToString(",") { date ->
                                    String.format("%04d%02d%02d", date.year, date.monthNumber, date.day)
                                }
                                put(CalendarContract.Events.EXDATE, exdateStr)
                            }
                        }
                    }
                    app.contentResolver.bulkInsert(
                        CalendarContract.Events.CONTENT_URI,
                        valuesList.toTypedArray(),
                    )
                    _events.value = Event.getAllEvents(app)
                } catch (e: Exception) {
                    Log.e("CalendarViewModel", "Error importing events", e)
                }
            }
            updateWidgets()
            onDone()
        }
    }

    fun loadData() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _events.value = Event.getAllEvents(app)

            val loaded = Calendar.getAllCalendars(app)
            _calendars.value = loaded

            // initialize visibility from provider's VISIBLE flag
            val visMap = loaded.associate { cal -> cal.id to cal.visible }
            _calendarVisibility.value = visMap
        }
    }

    init {
        loadData()
        viewModelScope.launch {
            dataStore.stringFlow("default_calendar_layout").collect { saved ->
                try {
                    _currentLayout.value = CalendarLayout.valueOf(saved)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun updateWidgets() {
        viewModelScope.launch {
            CalendarGlanceWidget().updateAll(getApplication())
        }
    }

    fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        val app = getApplication<Application>()
        // write to the provider's Calendars.VISIBLE field for that calendar
        val values = ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        try {
            app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        } catch (e: Exception) {
            Log.e("CalendarViewModel", "Error setting calendar visibility", e)
        }

        // refresh cached calendars and visibility map
        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    fun deleteEventSeries(eventId: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            upsertEvent(eventId, ContentValues().apply {
                put(CalendarContract.Events.DELETED, 1)
            })
            _events.value = Event.getAllEvents(app)
            updateWidgets()
        }
    }

    fun deleteEventInstance(eventId: Long, instanceBeginTime: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Get the event to find its timezone and current exdate
            val event = _events.value.find { it.id == eventId }
            if (event != null) {
                // Convert instanceBeginTime to LocalDate in event's timezone
                val instanceDate = kotlinx.datetime.Instant.fromEpochMilliseconds(instanceBeginTime)
                    .toLocalDateTime(kotlinx.datetime.TimeZone.of(event.timezone)).date
                
                // Add date to exdate list (avoid duplicates)
                val newExdate = (event.exdate + instanceDate).distinct()
                
                // Format as comma-separated RFC 5545 dates (YYYYMMDD)
                val exdateStr = newExdate.joinToString(",") { date ->
                    String.format("%04d%02d%02d", date.year, date.monthNumber, date.day)
                }
                
                upsertEvent(eventId, ContentValues().apply {
                    put(CalendarContract.Events.EXDATE, exdateStr)
                })
                _events.value = Event.getAllEvents(app)
                updateWidgets()
            }
        }
    }

    // Insert or update event using ContentValues. If eventId is null -> insert, otherwise update.
    fun upsertEvent(eventId: Long?, values: ContentValues): Long? {
        val app = getApplication<Application>()
        val uri = CalendarContract.Events.CONTENT_URI
        return if (eventId == null) {
            try {
                val newUri = app.contentResolver.insert(uri, values)
                // refresh events
                _events.value = Event.getAllEvents(app)
                updateWidgets()
                newUri?.lastPathSegment?.toLongOrNull()
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error inserting event", e)
                null
            }
        } else {
            try {
                app.contentResolver.update(uri, values, "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()))
                _events.value = Event.getAllEvents(app)
                updateWidgets()
                eventId
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error updating event", e)
                null
            }
        }
    }

    // set the calendar color in the provider and refresh cached calendars
    fun setCalendarColor(calendarId: Long, colorInt: Int) {
        val app = getApplication<Application>()
        val values = ContentValues().apply { put(CalendarContract.Calendars.CALENDAR_COLOR, colorInt) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        try {
            app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        } catch (e: Exception) {
            Log.e("CalendarViewModel", "Error setting calendar color", e)
        }

        // refresh cached calendars and visibility map (color is read from provider)
        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    // rename a calendar's display name
    fun renameCalendar(calendarId: Long, newDisplayName: String) {
        val app = getApplication<Application>()
        val cal = calendars.value.find { it.id == calendarId }
        if (cal == null || !cal.canModify) {
            Log.e("CalendarViewModel", "Attempted to rename a readonly or non-existent calendar")
            return
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, newDisplayName)
            put(CalendarContract.Calendars.NAME, newDisplayName)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        try {
            app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        } catch (e: Exception) {
            Log.e("CalendarViewModel", "Error renaming calendar", e)
        }

        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    // delete a calendar and refresh caches
    fun deleteCalendar(calendarId: Long) {
        val app = getApplication<Application>()
        val cal = calendars.value.find { it.id == calendarId }
        if (cal == null || !cal.canModify) {
            Log.e("CalendarViewModel", "Attempted to delete a readonly or non-existent calendar")
            return
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        try {
            app.contentResolver.delete(uri, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        } catch (e: Exception) {
            // ignore deletion errors, we'll refresh list anyway
            Log.e("CalendarViewModel", "Error deleting calendar", e)
        }

        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    // create a new local/offline calendar in the provider and refresh caches
    fun createLocalCalendar(accountName: String, displayName: String, colorInt: Int, visible: Boolean, accessLevel: Int) {
        viewModelScope.launch {
            val app = getApplication<Application>()

            // To insert calendars with custom account fields we need to use the sync adapter flag
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()

            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(CalendarContract.Calendars.NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_COLOR, colorInt)
                put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, accessLevel)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.currentSystemDefault().id)
            }

            try {
                val newUri = app.contentResolver.insert(uri, values)
                if (newUri != null) {
                    val loaded = Calendar.getAllCalendars(app)
                    _calendars.value = loaded
                    _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
                    updateWidgets()
                }
            } catch (_: Exception) {
                // some providers reject inserts; refresh list anyway
                val loaded = Calendar.getAllCalendars(app)
                _calendars.value = loaded
                _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
                updateWidgets()
            }
        }
    }

}