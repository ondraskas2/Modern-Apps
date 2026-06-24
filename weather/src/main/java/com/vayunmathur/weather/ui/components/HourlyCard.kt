package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.formatTemperatureCompact
import com.vayunmathur.weather.util.weatherConditionForCode
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Instant

/**
 * Direct port of WeatherMaster's `HourlyCard`. `Surface(shape = extraLarge,
 * color = surface, shadowElevation = 2.dp)`, [CardsHeader] at top, then a
 * `LazyRow` of 120 dp × 45 dp items. First (current-hour) item shows its
 * temperature inside a `MaterialShapes.Cookie4Sided` pill filled with
 * `primary`.
 */
@Composable
fun HourlyCard(
    hourly: Hourly,
    tempUnit: TemperatureUnit,
    utcOffsetSeconds: Int = 0,
    use24Hour: Boolean = false,
    selectedIsoTime: String? = null,
    onHourSelected: (String) -> Unit = {},
    scrollToIsoDate: String? = null,
) {
    val nowSec = System.currentTimeMillis() / 1000
    val cells = hourly.time.indices
        .mapNotNull { i ->
            val iso = hourly.time.getOrNull(i) ?: return@mapNotNull null
            val ts = parseIsoToEpochSec(iso, utcOffsetSeconds) ?: return@mapNotNull null
            if (ts < nowSec - 3600) return@mapNotNull null
            HourCell(
                iso = iso,
                epochSec = ts,
                temperature = hourly.temperature.getOrNull(i) ?: 0.0,
                weatherCode = hourly.weatherCode.getOrNull(i) ?: 0,
                precip = hourly.precipitationProbability.getOrNull(i) ?: 0,
                isDay = (hourly.isDay.getOrNull(i) ?: 1) == 1,
            )
        }
    if (cells.isEmpty()) return

    val listState = rememberLazyListState()
    LaunchedEffect(scrollToIsoDate, cells) {
        if (scrollToIsoDate != null) {
            val target = cells.indexOfFirst { it.iso.substringBefore('T') == scrollToIsoDate }
            if (target >= 0) listState.animateScrollToItem(target)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            CardsHeader(text = "Hourly forecast", iconRes = R.drawable.outline_schedule_24)
            LazyRow(state = listState) {
                items(cells.size, key = { "${cells[it].epochSec}_$it" }) { index ->
                    val cell = cells[index]
                    if (index == 0) Spacer(Modifier.width(10.dp))
                    HourlyItem(
                        time = if (index == 0) "Now" else formatHour(cell.epochSec, use24Hour),
                        dayLabel = formatDayLabel(cell.epochSec),
                        precipitationProbability = cell.precip,
                        temperature = cell.temperature,
                        isNow = index == 0,
                        isSelected = selectedIsoTime == cell.iso,
                        iconRes = weatherConditionForCode(cell.weatherCode).iconRes(cell.isDay),
                        tempUnit = tempUnit,
                        onClick = { onHourSelected(cell.iso) },
                    )
                    if (index == cells.size - 1) Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

@Composable
private fun HourlyItem(
    time: String,
    dayLabel: String,
    precipitationProbability: Int,
    temperature: Double,
    isNow: Boolean,
    isSelected: Boolean,
    iconRes: Int,
    tempUnit: TemperatureUnit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.height(135.dp).width(45.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(5.dp))
        TempWithShape(temperature = temperature, tempUnit = tempUnit, highlighted = isNow || isSelected)
        Spacer(Modifier.height(2.dp))
        Text(
            "${precipitationProbability}%",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(bottom = 3.dp)
                .alpha(if (precipitationProbability > 0) 1f else 0f),
        )
        WeatherIconBox(iconRes = iconRes, size = 28.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            time,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            dayLabel,
            color = Color.Gray,
            fontSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TempWithShape(temperature: Double, tempUnit: TemperatureUnit, highlighted: Boolean) {
    Surface(
        shape = MaterialShapes.Cookie4Sided.toShape(),
        modifier = Modifier.size(36.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                formatTemperatureCompact(temperature, tempUnit),
                color = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private data class HourCell(
    val iso: String,
    val epochSec: Long,
    val temperature: Double,
    val weatherCode: Int,
    val precip: Int,
    val isDay: Boolean,
)

private fun parseIsoToEpochSec(iso: String?, utcOffsetSeconds: Int = 0): Long? {
    if (iso == null) return null
    // Open-Meteo returns local time strings when timezone=auto
    // We need to parse them as local time and apply the UTC offset
    return runCatching {
        // Parse as local datetime, then convert to instant using the offset
        val localDateTime = kotlinx.datetime.LocalDateTime.parse(iso)
        val offset = kotlinx.datetime.UtcOffset(seconds = utcOffsetSeconds)
        localDateTime.toInstant(offset).epochSeconds
    }.getOrNull()
        ?: runCatching { Instant.parse("$iso:00Z").epochSeconds }.getOrNull()
        ?: runCatching { Instant.parse(iso).epochSeconds }.getOrNull()
}

private fun formatHour(epochSec: Long, use24Hour: Boolean): String {
    val ldt = Instant.fromEpochSeconds(epochSec).toLocalDateTime(TimeZone.currentSystemDefault())
    val h = ldt.hour
    if (use24Hour) return "$h"
    val ampm = if (h < 12) "AM" else "PM"
    val display = if (h % 12 == 0) 12 else h % 12
    return "$display $ampm"
}

private fun formatDayLabel(epochSec: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochSeconds(epochSec).toLocalDateTime(tz).date
    val today = Clock.System.todayIn(tz)
    val tomorrow = today.plus(1, DateTimeUnit.DAY)
    return when (date) {
        today -> "TDY"
        tomorrow -> "TMR"
        else -> date.dayOfWeek.name.take(3)
    }
}
