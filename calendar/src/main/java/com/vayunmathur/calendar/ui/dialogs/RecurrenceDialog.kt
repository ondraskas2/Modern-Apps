package com.vayunmathur.calendar.ui.dialogs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.ui.dateFormat
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.ResultEffect
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format

private const val KEY_UNTIL = "RecurranceDialog.until"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceDialog(backStack: NavBackStack<Route>, resultKey: String, startDate: LocalDate, initial: RecurrenceParams?) {
    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()

    var freq by remember { mutableStateOf(initial?.freq ?: "days") }
    var intervalStr by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var monthlyType by remember { mutableIntStateOf(initial?.monthlyType ?: 0) }
    var daysOfWeek by remember { mutableStateOf(initial?.daysOfWeek ?: emptyList()) }
    var endCondition by remember { mutableStateOf(initial?.endCondition ?: RRule.EndCondition.Never) }
    
    // RFC 5545 properties
    var byMonthDay by remember { mutableStateOf(initial?.byMonthDay ?: emptyList()) }
    var byMonth by remember { mutableStateOf(initial?.byMonth ?: emptyList()) }
    var bySetPos by remember { mutableStateOf(initial?.bySetPos ?: emptyList()) }
    var byYearDay by remember { mutableStateOf(initial?.byYearDay ?: emptyList()) }
    var byWeekNo by remember { mutableStateOf(initial?.byWeekNo ?: emptyList()) }
    var wkst by remember { mutableStateOf(initial?.wkst) }

    // result key for the nested date picker used for UNTIL
    // listen for date picker result
    ResultEffect<LocalDate>(KEY_UNTIL) { selected ->
        endCondition = RRule.EndCondition.Until(selected)
    }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                val params = RecurrenceParams(
                    freq = freq,
                    interval = intervalStr.toIntOrNull() ?: 1,
                    daysOfWeek = daysOfWeek,
                    monthlyType = monthlyType,
                    endCondition = endCondition,
                    byMonthDay = byMonthDay,
                    byMonth = byMonth,
                    bySetPos = bySetPos,
                    byYearDay = byYearDay,
                    byWeekNo = byWeekNo,
                    wkst = wkst
                )

                val rrule = params.let { p ->
                    when (p.freq) {
                        "days" -> RRule.EveryXDays(
                            p.interval, p.endCondition,
                            p.byMonthDay.ifEmpty { null },
                            p.byMonth.ifEmpty { null },
                            p.bySetPos.ifEmpty { null },
                            p.byYearDay.ifEmpty { null },
                            p.byWeekNo.ifEmpty { null },
                            p.wkst
                        )
                        "weeks" -> RRule.EveryXWeeks(
                            p.interval, p.daysOfWeek, p.endCondition,
                            p.byMonthDay.ifEmpty { null },
                            p.byMonth.ifEmpty { null },
                            p.bySetPos.ifEmpty { null },
                            p.byYearDay.ifEmpty { null },
                            p.byWeekNo.ifEmpty { null },
                            p.wkst
                        )
                        "months" -> RRule.EveryXMonths(
                            p.interval, p.monthlyType, p.endCondition,
                            p.byMonthDay.ifEmpty { null },
                            p.byMonth.ifEmpty { null },
                            p.bySetPos.ifEmpty { null },
                            p.byYearDay.ifEmpty { null },
                            p.byWeekNo.ifEmpty { null },
                            p.wkst
                        )
                        "years" -> RRule.EveryXYears(
                            p.interval, p.endCondition,
                            p.byMonthDay.ifEmpty { null },
                            p.byMonth.ifEmpty { null },
                            p.bySetPos.ifEmpty { null },
                            p.byYearDay.ifEmpty { null },
                            p.byWeekNo.ifEmpty { null },
                            p.wkst
                        )
                        else -> null
                    }
                } ?: ""

                scope.launch { registry.dispatchResult(resultKey, rrule) }
                backStack.pop()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(Modifier.padding(8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.repeat))
                    var openDropdown by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        intervalStr,
                        { intervalStr = it },
                        leadingIcon = {Text(stringResource(R.string.every))},
                        trailingIcon = {
                            Text(stringResource(R.string.dropdown_freq_format, freq), Modifier.clickable{
                                openDropdown = true
                            })
                            DropdownMenu(openDropdown, onDismissRequest = { openDropdown = false }) {
                                listOf("days", "weeks", "months", "years").forEach { f ->
                                    DropdownMenuItem({ Text(f) }, onClick = {
                                        freq = f
                                        openDropdown = false
                                    })
                                }
                            }
                        }
                    )
                }

                if (freq == "weeks") {
                    Text(stringResource(R.string.on_days_of_week))
                    val dayOfWeekCircle = @Composable { d: DayOfWeek ->
                        Surface(Modifier.clickable {
                            daysOfWeek = if (daysOfWeek.contains(d)) daysOfWeek - d else daysOfWeek + d
                        }, color = if(d in daysOfWeek) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape) {
                            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                                Text(d.name.take(3).lowercase().replaceFirstChar { it.titlecase() })
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayOfWeek.entries.take(4).forEach {
                                dayOfWeekCircle(it)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayOfWeek.entries.drop(4).forEach {
                                dayOfWeekCircle(it)
                            }
                        }
                    }
                }

                if (freq == "months") {
                    Text(stringResource(R.string.monthly_type))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(monthlyType == 0, {monthlyType = 0}, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                            Text(ordinal(startDate.day))
                        }
                        SegmentedButton(monthlyType == 1, {monthlyType = 1}, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                            Text(stringResource(R.string.monthly_nth_day_format, ordinal((startDate.day-1)/7+1), startDate.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }))
                        }
                    }
                }

                // BYMONTHDAY - specific days of month
                if (freq == "months" || freq == "years") {
                    var byMonthDayStr by remember { mutableStateOf(byMonthDay.joinToString(",")) }
                    OutlinedTextField(
                        byMonthDayStr,
                        { new ->
                            byMonthDayStr = new
                            byMonthDay = new.split(",").mapNotNull { it.trim().toIntOrNull() }
                        },
                        label = { Text(stringResource(R.string.by_month_day_label)) },
                        placeholder = { Text(stringResource(R.string.by_month_day_placeholder)) }
                    )
                }

                // BYMONTH - specific months
                if (freq == "years") {
                    var byMonthStr by remember { mutableStateOf(byMonth.joinToString(",")) }
                    OutlinedTextField(
                        byMonthStr,
                        { new ->
                            byMonthStr = new
                            byMonth = new.split(",").mapNotNull { it.trim().toIntOrNull() }
                        },
                        label = { Text(stringResource(R.string.by_month_label)) },
                        placeholder = { Text(stringResource(R.string.by_month_placeholder)) }
                    )
                }

                // BYSETPOS - position in set
                var bySetPosStr by remember { mutableStateOf(bySetPos.joinToString(",")) }
                OutlinedTextField(
                    bySetPosStr,
                    { new ->
                        bySetPosStr = new
                        bySetPos = new.split(",").mapNotNull { it.trim().toIntOrNull() }
                    },
                    label = { Text(stringResource(R.string.by_set_pos_label)) },
                    placeholder = { Text(stringResource(R.string.by_set_pos_placeholder)) }
                )

                // BYYEARDAY - day of year
                if (freq == "years") {
                    var byYearDayStr by remember { mutableStateOf(byYearDay.joinToString(",")) }
                    OutlinedTextField(
                        byYearDayStr,
                        { new ->
                            byYearDayStr = new
                            byYearDay = new.split(",").mapNotNull { it.trim().toIntOrNull() }
                        },
                        label = { Text(stringResource(R.string.by_year_day_label)) },
                        placeholder = { Text(stringResource(R.string.by_year_day_placeholder)) }
                    )
                }

                // BYWEEKNO - week number
                if (freq == "years") {
                    var byWeekNoStr by remember { mutableStateOf(byWeekNo.joinToString(",")) }
                    OutlinedTextField(
                        byWeekNoStr,
                        { new ->
                            byWeekNoStr = new
                            byWeekNo = new.split(",").mapNotNull { it.trim().toIntOrNull() }
                        },
                        label = { Text(stringResource(R.string.by_week_no_label)) },
                        placeholder = { Text(stringResource(R.string.by_week_no_placeholder)) }
                    )
                }

                // WKST - week start day
                var wkstDropdownOpen by remember { mutableStateOf(false) }
                OutlinedTextField(
                    wkst?.name?.take(2) ?: "MO",
                    { },
                    readOnly = true,
                    label = { Text(stringResource(R.string.week_start_label)) },
                    trailingIcon = {
                        Text(
                            wkst?.name?.take(2) ?: "MO",
                            Modifier.clickable { wkstDropdownOpen = true }
                        )
                        DropdownMenu(wkstDropdownOpen, onDismissRequest = { wkstDropdownOpen = false }) {
                            DayOfWeek.entries.forEach { day ->
                                DropdownMenuItem(
                                    text = { Text(day.name.take(2)) },
                                    onClick = {
                                        wkst = day
                                        wkstDropdownOpen = false
                                    }
                                )
                            }
                        }
                    }
                )

                Text(stringResource(R.string.end))

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(endCondition is RRule.EndCondition.Never, {endCondition = RRule.EndCondition.Never}, shape = SegmentedButtonDefaults.itemShape(0, 3)) {
                        Text(stringResource(R.string.never))
                    }
                    SegmentedButton(endCondition is RRule.EndCondition.Count, {endCondition = RRule.EndCondition.Count(1)}, shape = SegmentedButtonDefaults.itemShape(1, 3)) {
                        Text(stringResource(R.string.count))
                    }
                    SegmentedButton(endCondition is RRule.EndCondition.Until, {endCondition = RRule.EndCondition.Until(startDate)}, shape = SegmentedButtonDefaults.itemShape(2, 3)) {
                        Text(stringResource(R.string.until))
                    }
                }
                if (endCondition is RRule.EndCondition.Count) {
                    var countStr by remember { mutableStateOf((endCondition as RRule.EndCondition.Count).count.toString()) }
                    OutlinedTextField(countStr, { new ->
                        val v = new.toLongOrNull() ?: 1L
                        countStr = new
                        endCondition = RRule.EndCondition.Count(v)
                    }, label = { Text(stringResource(R.string.count)) })
                }
                if(endCondition is RRule.EndCondition.Until) {
                    OutlinedTextField(
                        stringResource(R.string.until_date, (endCondition as RRule.EndCondition.Until).date.format(dateFormat)),
                        { },
                        readOnly = true,
                        interactionSource = remember { MutableInteractionSource() }
                            .also { interactionSource ->
                                LaunchedEffect(interactionSource) {
                                    interactionSource.interactions.collect {
                                        if (it is PressInteraction.Release) {
                                            val current = endCondition as RRule.EndCondition.Until
                                            backStack.add(Route.EditEvent.DatePickerDialog(KEY_UNTIL, current.date, startDate))
                                        }
                                    }
                                }
                            }
                    )
                }
            }
        }
    )
}

private fun ordinal(int: Int): String {
    return int.toString() + (when (int % 100) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        in 4..20 -> "th"
        else -> null
    } ?: when (int % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    })
}
