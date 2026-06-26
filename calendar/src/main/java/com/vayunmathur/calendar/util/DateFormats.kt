package com.vayunmathur.calendar.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toLocalDateTime

// Full yyyyMMdd'T'HHmmssZ pattern (ISO_BASIC handles 'Z' or '+HHmm').
val BasicIsoInstantFormat = DateTimeComponents.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
    offset(UtcOffset.Formats.ISO_BASIC)
}

// Date-only yyyyMMdd (RFC 5545 basic date).
val AllDayFormat = LocalDate.Format {
    year(); monthNumber(); day()
}

/** RFC 5545 basic date string (YYYYMMDD) for this date. */
fun LocalDate.toIcalBasic(): String = format(AllDayFormat)

/** Parses a basic iCal date (YYYYMMDD, optionally followed by a time) to a [LocalDate]. */
fun parseIcalBasicDate(value: String): LocalDate? =
    runCatching { AllDayFormat.parse(value.take(8)) }.getOrNull()

/** Parses an RFC 5545 UNTIL value (date or datetime) to a [LocalDate] in [timeZone]. */
fun parseIcalUntil(value: String, timeZone: TimeZone): LocalDate? = runCatching {
    if ('T' in value) {
        BasicIsoInstantFormat.parse(value).toInstantUsingOffset().toLocalDateTime(timeZone).date
    } else {
        AllDayFormat.parse(value)
    }
}.getOrNull()
