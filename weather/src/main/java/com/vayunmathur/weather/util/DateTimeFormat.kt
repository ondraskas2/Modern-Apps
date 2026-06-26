package com.vayunmathur.weather.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Centralized date/time display formatting for the weather module, built on
 * kotlinx-datetime format builders instead of hand-rolled weekday/month/hour
 * arrays. Every helper preserves the exact strings the screens rendered before.
 */

/** "3 PM" / "12 AM" — 12-hour clock, no minutes. */
private val HourAmPm = LocalTime.Format {
    amPmHour(Padding.NONE)
    char(' ')
    amPmMarker("AM", "PM")
}

/** "09:00" / "15:00" — 24-hour hour, padded, minutes pinned to :00 for hour axes. */
private val HourOfDayWithZero = LocalTime.Format {
    hour()
    char(':')
    char('0')
    char('0')
}

/** "9" / "15" — bare 24-hour hour, no padding. */
private val HourOfDayBare = LocalTime.Format {
    hour(Padding.NONE)
}

/** "3:05 PM" / "12:00 AM" — 12-hour clock with minutes. */
private val ClockTimeAmPm = LocalTime.Format {
    amPmHour(Padding.NONE)
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}

/** "09:05" / "15:00" — 24-hour clock with minutes. */
private val ClockTime24 = LocalTime.Format {
    hour()
    char(':')
    minute()
}

/** "Mon" … "Sun". */
private val WeekdayShort = LocalDate.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
}

/** "Wed 25 Jun". */
private val DayMonthLabel = LocalDate.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    char(' ')
    day(Padding.NONE)
    char(' ')
    monthName(MonthNames.ENGLISH_ABBREVIATED)
}

private fun localTimeAt(epochSec: Long): LocalTime =
    Instant.fromEpochSeconds(epochSec).toLocalDateTime(TimeZone.currentSystemDefault()).time

/** Hour-axis label "3 PM" / "09:00" for an epoch second in the system time zone. */
fun formatHourAxisLabel(epochSec: Long, use24Hour: Boolean): String =
    localTimeAt(epochSec).format(if (use24Hour) HourOfDayWithZero else HourAmPm)

/** Hourly-strip label "3 PM" / "9" for an epoch second in the system time zone. */
fun formatStripHour(epochSec: Long, use24Hour: Boolean): String =
    localTimeAt(epochSec).format(if (use24Hour) HourOfDayBare else HourAmPm)

/** Clock time "3:05 PM" / "15:00" for an epoch second in the system time zone. */
fun formatClockTime(epochSec: Long, use24Hour: Boolean): String =
    localTimeAt(epochSec).format(if (use24Hour) ClockTime24 else ClockTimeAmPm)

/** "Wed 25 Jun" for an ISO date like 2026-06-25; echoes the input on parse failure. */
fun formatDayMonthLabel(isoDate: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return date.format(DayMonthLabel)
}

/** "3 PM · Wed" (or "15:00 · Wed") for an ISO time like 2026-06-25T15:00. */
fun formatSelectedHourLabel(isoTime: String, use24Hour: Boolean): String {
    val ldt = runCatching { LocalDateTime.parse(isoTime) }.getOrNull() ?: return isoTime
    val hour = ldt.time.format(if (use24Hour) HourOfDayWithZero else HourAmPm)
    val weekday = ldt.date.format(WeekdayShort)
    return "$hour · $weekday"
}

/**
 * Open-Meteo returns local-time ISO strings with no offset (e.g.
 * `2024-05-30T05:42`). Combine with `utc_offset_seconds` from the response to
 * recover a true epoch, falling back to explicit-offset / `Z` strings.
 */
fun parseLocalIsoToEpochSec(iso: String, utcOffsetSec: Int = 0): Long? = runCatching {
    LocalDateTime.parse(iso).toInstant(UtcOffset(seconds = utcOffsetSec)).epochSeconds
}.getOrNull()
    ?: runCatching { Instant.parse("$iso:00Z").epochSeconds }.getOrNull()
    ?: runCatching { Instant.parse(iso).epochSeconds }.getOrNull()
