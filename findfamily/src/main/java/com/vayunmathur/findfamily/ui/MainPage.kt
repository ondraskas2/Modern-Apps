package com.vayunmathur.findfamily.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toPosition
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.formatSpeed
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainPage(
    platform: Platform,
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    initialUserId: Long? = null,
    initialWaypointId: Long? = null
) {
    // Mirror the original `remember(initialUserId)` behaviour: apply the
    // navigation-supplied selection whenever it changes.
    LaunchedEffect(initialUserId, initialWaypointId) {
        ffViewModel.applyInitialSelection(initialUserId, initialWaypointId)
    }

    val selectedUserId by ffViewModel.selectedUserId.collectAsState()
    val selectedWaypointId by ffViewModel.selectedWaypointId.collectAsState()
    val isShowingPresent by ffViewModel.isShowingPresent.collectAsState()
    val historicalPosition by ffViewModel.historicalPosition.collectAsState()

    val waypointName by ffViewModel.waypointName.collectAsState()
    val waypointRange by ffViewModel.waypointRange.collectAsState()

    BackHandler(selectedUserId != null || (selectedWaypointId != null && selectedWaypointId != 0L)) {
        ffViewModel.clearSelection()
    }

    val temporaryLinks by ffViewModel.temporaryLinks.collectAsState()
    val waypoints by ffViewModel.waypoints.collectAsState()

    val connectedUsers by ffViewModel.connectedUsers.collectAsState()
    val awaitingRequestUsers by ffViewModel.awaitingRequestUsers.collectAsState()
    val usersByLocationName by ffViewModel.usersByLocationName.collectAsState()
    val userPositions by ffViewModel.latestLocationByUser.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedUserId == null && selectedWaypointId == null) {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    if (selectedUserId != null || selectedWaypointId != null) {
                        IconNavigation {
                            ffViewModel.clearSelection()
                        }
                    }
                },
                actions = {
                    if (selectedUserId == null && (selectedWaypointId == null || selectedWaypointId == 0L)) {
                        BackupButtons(
                            dbConfigs = listOf("passwords-db" to ffViewModel.backupPassphrase),
                            extraFiles = emptyList()
                        )
                    } else if (selectedUserId != null) {
                        if (selectedUserId != Networking.userid) {
                            val user by ffViewModel.userByIdState(selectedUserId!!)
                            // UWB Find Nearby (UWB) requires the public android.ranging API
                            // (Android 15+). Hide the entry point on older devices.
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                IconButton({
                                    backStack.add(Route.UwbRangingPage(selectedUserId!!))
                                }) {
                                    IconNavigationArrow()
                                }
                            }
                            IconButton({
                                user?.let { ffViewModel.deleteUser(it) }
                                ffViewModel.setSelectedUserId(null)
                            }) {
                                IconDelete()
                            }
                        }
                    } else if (selectedWaypointId != null && selectedWaypointId != 0L) {
                        val waypoint by ffViewModel.waypointByIdState(selectedWaypointId!!)
                        IconButton({
                            waypoint?.let { ffViewModel.deleteWaypoint(it) }
                            ffViewModel.setSelectedWaypointId(null)
                        }) {
                            IconDelete()
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedUserId == null && selectedWaypointId == null) {
                var expanded by remember { mutableStateOf(false) }
                FloatingActionButtonMenu(expanded, {
                    ToggleFloatingActionButton(expanded, { expanded = it }) {
                        if (!expanded)
                            IconAdd()
                        else
                            IconClose()
                    }
                }) {
                    FloatingActionButtonMenuItem({
                        backStack.add(Route.AddPersonDialog())
                    },
                        { Text(stringResource(R.string.fab_person)) },
                        { Icon(painterResource(R.drawable.outline_person_24), null) })
                    FloatingActionButtonMenuItem({
                        ffViewModel.beginCreateWaypoint()
                    },
                        { Text(stringResource(R.string.fab_location)) },
                        { Icon(painterResource(R.drawable.outline_pin_drop_24), null) })
                    FloatingActionButtonMenuItem({
                        backStack.add(Route.AddLinkDialog)
                    },
                        { Text(stringResource(R.string.fab_link)) },
                        { Icon(painterResource(R.drawable.outline_link_24), null) })
                }
            } else if (selectedWaypointId != null) {
                FloatingActionButton({
                    ffViewModel.saveCurrentWaypoint()
                }) {
                    IconSave()
                }
            }
        }, bottomBar = {
            Surface(
                Modifier.heightIn(max = 400.dp).padding(BottomAppBarDefaults.windowInsets.asPaddingValues()),
                color = MaterialTheme.colorScheme.background
            ) {
                if (selectedUserId == null && selectedWaypointId == null) {
                    LazyColumn(
                        Modifier.padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        items(
                            connectedUsers,
                            key = { it.id }
                        ) {
                            UserCard(it, userPositions[it.id], true) {
                                ffViewModel.selectUser(it.id)
                            }
                        }
                        if (awaitingRequestUsers.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.section_location_sharing_requests),
                                    Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        items(
                            awaitingRequestUsers,
                            key = { it.id }
                        ) {
                            AwaitingRequestCard(backStack, it.id)
                        }
                        if (temporaryLinks.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.section_temporary_links),
                                    Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        items(temporaryLinks, key = { it.id }) {
                            TemporaryLinkCard(platform, ffViewModel, it)
                        }
                        item {
                            if (waypoints.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.section_saved_places),
                                    Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        items(waypoints, key = { it.id }) {
                            WaypointCard(it, usersByLocationName[it.name].orEmpty()) {
                                ffViewModel.beginEditWaypoint(it)
                            }
                        }
                    }
                } else if (selectedUserId != null) {
                    val selectedUser by ffViewModel.userByIdState(selectedUserId!!)
                    val requestPickContact = platform.requestPickContact { name, photo ->
                        selectedUser?.let { ffViewModel.upsertUser(it.copy(name = name, photo = photo)) }
                    }
                    Column {
                        selectedUser?.let { user ->
                            UserCard(user, userPositions[user.id], true) {}
                            Spacer(Modifier.height(4.dp))
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Card {
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.share_your_location))
                                        Spacer(Modifier.weight(1f))
                                        Checkbox(
                                            user.sendingEnabled,
                                            { send ->
                                                ffViewModel.setUserSharing(user, send)
                                            })
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton({
                                    requestPickContact()
                                }) {
                                    Text(stringResource(R.string.change_connected_contact))
                                }
                            }
                        }
                    }
                } else if (selectedWaypointId != null) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            waypointName,
                            { ffViewModel.setWaypointName(it) },
                            Modifier.fillMaxWidth(),
                            isError = waypointName.isBlank(),
                            supportingText = if (waypointName.isBlank()) {
                                { Text(stringResource(R.string.waypoint_name_blank_error)) }
                            } else null
                        )
                        Spacer(Modifier.heightIn(8.dp))
                        OutlinedTextField(
                            waypointRange,
                            { ffViewModel.setWaypointRange(it) },
                            Modifier.fillMaxWidth(),
                            suffix = { Text(stringResource(R.string.waypoint_range_suffix)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            isError = waypointRange.toDoubleOrNull() == null,
                            supportingText = if (waypointRange.toDoubleOrNull() == null) {
                                { Text(stringResource(R.string.waypoint_range_error)) }
                            } else null
                        )
                    }
                }
            }
        }) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxWidth()) {
            val selectedUserObj = if (selectedUserId != null) {
                val user by ffViewModel.userByIdState(selectedUserId!!)
                user?.let { SelectedUser(it, isShowingPresent, historicalPosition) }
            } else null

            val selectedWaypointObj = if (selectedWaypointId != null) {
                val waypoint by ffViewModel.waypointByIdState(selectedWaypointId!!) { Waypoint.NEW_WAYPOINT }
                waypoint?.let { wp -> SelectedWaypoint(wp, waypointRange.toDoubleOrNull() ?: 0.0) {
                    ffViewModel.setWaypointCoord(it)
                } }
            } else null

            MapView(
                ffViewModel,
                onUserClick = {
                    ffViewModel.selectUser(it)
                },
                onMapClick = {
                    ffViewModel.clearSelection()
                },
                selectedUser = selectedUserObj,
                selectedWaypoint = selectedWaypointObj
            )

            if (selectedUserId != null) {
                HistoryBar(
                    backStack,
                    isShowingPresent,
                    { ffViewModel.setShowingPresent(it) },
                    ffViewModel,
                    selectedUserId!!
                ) { ffViewModel.setHistoricalPosition(it) }
            }
        }
    }

    // Map animation logic
    LaunchedEffect(selectedUserId, isShowingPresent, historicalPosition) {
        if (selectedUserId != null) {
            val targetPosition = if (isShowingPresent) {
                userPositions[selectedUserId!!]?.coord?.toPosition()
            } else {
                historicalPosition
            }
            targetPosition?.let {
                camera.animateTo(
                    camera.position.copy(
                        target = it,
                        zoom = 15.0
                    )
                )
            }
        }
    }

    LaunchedEffect(selectedWaypointId) {
        if (selectedWaypointId != null && selectedWaypointId != 0L) {
            val waypoint = waypoints.find { it.id == selectedWaypointId }
            waypoint?.coord?.toPosition()?.let {
                camera.animateTo(
                    camera.position.copy(
                        target = it,
                        zoom = 15.0
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.HistoryBar(
    backStack: NavBackStack<Route>,
    isShowingPresent: Boolean,
    setShowingPresent: (Boolean) -> Unit,
    ffViewModel: FindFamilyViewModel,
    userid: Long,
    setHistoricalPosition: (org.maplibre.spatialk.geojson.Position) -> Unit
) {
    Card(Modifier.width(105.dp).padding(2.dp).align(Alignment.BottomEnd)) {
        val colmod = if (isShowingPresent) Modifier else Modifier.fillMaxHeight(1f)
        Column(colmod.padding(4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isShowingPresent) {
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val currentDate = now.date
                val currentTime = now.time
                var pickedLocalDate by remember { mutableStateOf(currentDate) }
                val sliderState = rememberSliderState(
                    currentTime.toSecondOfDay().toFloat(), valueRange = 0.0f..(24f * 60f * 60f - 0.1f)
                )

                sliderState.onValueChange = {
                    val maximum = if (currentDate == pickedLocalDate) currentTime.toSecondOfDay().toFloat() else null
                    if (maximum != null && it > maximum) sliderState.value = maximum
                    else sliderState.value = it
                }
                val pickedLocalTime by remember {
                    derivedStateOf {
                        LocalTime.fromSecondOfDay(sliderState.value.toInt())
                    }
                }
                Box(Modifier.weight(1f)) {
                    VerticalSlider(sliderState, reverseDirection = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({
                        sliderState.value -= 5 * 60
                    }) {
                        Text(stringResource(R.string.history_rewind_large))
                    }
                    IconButton({
                        sliderState.value += 5 * 60
                    }) {
                        Text(stringResource(R.string.history_forward_large))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({
                        sliderState.value -= 60
                    }) {
                        Text(stringResource(R.string.history_rewind_medium))
                    }
                    IconButton({
                        sliderState.value += 60
                    }) {
                        Text(stringResource(R.string.history_forward_medium))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({
                        sliderState.value -= 10
                    }) {
                        Text(stringResource(R.string.history_rewind_small))
                    }
                    IconButton({
                        sliderState.value += 10
                    }) {
                        Text(stringResource(R.string.history_forward_small))
                    }
                }
                Text(pickedLocalTime.format(DateFormats.TIME_SECOND_AM_PM), fontSize = 11.sp)

                ResultEffect<LocalDate>("HistoryDatePicker") {
                    pickedLocalDate = it
                }

                OutlinedButton({
                    backStack.add(Route.UserPageHistoryDatePicker(pickedLocalDate))
                }, Modifier.fillMaxWidth()) {
                    Text(pickedLocalDate.format(DateFormats.DATE_INPUT))
                }
                val simulatedTimestamp = pickedLocalDate.atTime(pickedLocalTime)
                    .toInstant(TimeZone.currentSystemDefault())

                val locs by ffViewModel.locationHistory.collectAsState()

                LaunchedEffect(simulatedTimestamp, locs) {
                    if (locs.isNotEmpty()) {
                        val closest = locs.minBy { (it.timestamp - simulatedTimestamp).absoluteValue }
                        setHistoricalPosition(closest.coord.toPosition())
                    }
                }
            }
            OutlinedButton({
                setShowingPresent(!isShowingPresent)
            }, Modifier.fillMaxWidth()) {
                Text(if (isShowingPresent) stringResource(R.string.history_button) else stringResource(R.string.hide_button), fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun AwaitingRequestCard(backStack: NavBackStack<Route>, id: Long) {
    Card {
        ListItem({
            Text(stringResource(R.string.request_from, id.encodeBase26()))
        }, trailingContent = {
            IconButton({
                backStack.add(Route.AddPersonDialog(id))
            }) {
                IconAdd()
            }
        })
    }
}

@Composable
fun TemporaryLinkCard(platform: Platform, ffViewModel: FindFamilyViewModel, temporaryLink: TemporaryLink) {
    val context = LocalContext.current
    Card {
        ListItem({
            Text(temporaryLink.name)
        }, Modifier, {}, {
            Text(stringResource(R.string.expires, timestring(temporaryLink.deleteAt, true, context)))
        }, trailingContent = {
            Row {
                IconButton({
                    platform.copy("https://findfamily.cc/view/${temporaryLink.id}#key=${temporaryLink.key}")
                }) {
                    IconCopy()
                }
                Spacer(Modifier.width(16.dp))
                IconButton({
                    ffViewModel.deleteTemporaryLink(temporaryLink)
                }) {
                    IconDelete()
                }
            }
        })
    }
}

@Composable
fun WaypointCard(waypoint: Waypoint, userNamesHere: List<String>, onSelect: () -> Unit) {
    val usersString = when (userNamesHere.size) {
        0 -> stringResource(R.string.nobody_here)
        1 -> stringResource(R.string.user_is_here, userNamesHere.first())
        else -> stringResource(R.string.users_are_here, userNamesHere.joinToString())
    }
    Card(Modifier.clickable(onClick = onSelect)) {
        ListItem(
            headlineContent = { Text(waypoint.name, fontWeight = FontWeight.Bold) },
            supportingContent = { Text(usersString) },
            trailingContent = {
                IconEdit()
            }
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun UserCard(user: User, locationValue: LocationValue?, showSupportingContent: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val lastUpdatedTime = locationValue?.let { timestring(it.timestamp, false, context) } ?: stringResource(R.string.last_updated_never)
    val speedString = (locationValue?.speed ?: 0f).formatSpeed()
    val sinceTime = user.lastLocationChangeTime.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeSinceEntry = Clock.System.now() - user.lastLocationChangeTime
    val sinceString = when {
        user.locationName == "Unnamed Location" -> ""
        timeSinceEntry < 60.seconds -> stringResource(R.string.since_just_now)
        timeSinceEntry < 15.minutes -> stringResource(R.string.since_minutes_ago, timeSinceEntry.inWholeMinutes)
        else -> {
            val formattedTime = sinceTime.format(LocalDateTime.Format {
                amPmHour(Padding.NONE)
                chars(":")
                minute()
                chars(" ")
                amPmMarker("am", "pm")
            })
            val formattedDate = when (sinceTime.date.toEpochDays() - Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()) {
                0L -> stringResource(R.string.today)
                1L -> stringResource(R.string.yesterday)
                else -> sinceTime.date.format(DateFormats.MONTH_DAY)
            }
            stringResource(R.string.since_time_date, formattedTime, formattedDate)
        }
    }
    Card(if (showSupportingContent) Modifier.clickable(onClick = onClick) else Modifier) {
        ListItem(
            leadingContent = {
                Column(Modifier.width(65.dp)) {
                    UserPicture(user, 65.dp)
                    Spacer(Modifier.height(4.dp))
                    locationValue?.battery?.let {
                        BatteryBar(it)
                    }
                }
            },
            headlineContent = { Text(user.name, fontWeight = FontWeight.Bold) },
            supportingContent = {
                if (showSupportingContent) {
                    Text(stringResource(R.string.user_card_status, lastUpdatedTime, user.locationName, sinceString))
                }
            }, trailingContent = {
                if (showSupportingContent) {
                    Text(speedString)
                }
            })
    }
}

@Composable
fun BatteryBar(percent: Float, width: Dp = 30.dp, height: Dp = 15.dp) {
    val color = when {
        percent > 50 -> Color.Green
        percent > 20 -> Color.Yellow
        else -> Color.Red
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(width, height).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxHeight().width((width * (percent / 100f))).background(color, RoundedCornerShape(4.dp)))
        }
        Text(stringResource(R.string.battery_percentage, percent.toInt()), fontSize = 12.sp)
    }
}

fun timestring(timestamp: Instant, future: Boolean, context: Context): String {
    val duration = (Clock.System.now() - timestamp).absoluteValue
    return when {
        duration.inWholeSeconds < 60 -> context.getString(if (future) R.string.time_very_soon else R.string.time_just_now)
        duration.inWholeMinutes < 60 -> context.getString(if (future) R.string.time_in_minutes else R.string.time_minutes_ago, duration.inWholeMinutes)
        duration.inWholeHours < 24 -> context.getString(if (future) R.string.time_in_hours else R.string.time_hours_ago, duration.inWholeHours)
        else -> context.getString(if (future) R.string.time_in_days else R.string.time_days_ago, duration.inWholeDays)
    }
}

object DateFormats {
    // example: Jun 4
    val MONTH_DAY = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        chars(" ")
        day()
    }

    // example: 10:05 am
    val TIME_SECOND_AM_PM = LocalTime.Format {
        amPmHour()
        chars(":")
        minute()
        chars(":")
        second()
        chars(" ")
        amPmMarker("AM", "PM")
    }

    val DATE_INPUT = MONTH_DAY
}
