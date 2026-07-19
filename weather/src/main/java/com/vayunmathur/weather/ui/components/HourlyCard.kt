package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.MaterialShapes
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.toShape
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
import com.vayunmathur.weather.util.formatStripHour
import com.vayunmathur.weather.util.formatTemperatureCompact
import com.vayunmathur.weather.util.parseLocalIsoToEpochSec
import com.vayunmathur.weather.util.weatherConditionForCode
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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
    val cells = androidx.compose.runtime.remember(hourly, utcOffsetSeconds) {
        val nowSec = System.currentTimeMillis() / 1000
        hourly.time.indices
            .mapNotNull { i ->
                val iso = hourly.time.getOrNull(i) ?: return@mapNotNull null
                val ts = parseLocalIsoToEpochSec(iso, utcOffsetSeconds) ?: return@mapNotNull null
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
    }
    if (cells.isEmpty()) return

    val listState = rememberLazyListState()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
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
            CardsHeader(text = "Hourly forecast", icon = { m, c -> IconSchedule(m, c) })
            LazyRow(
                state = listState,
                flingBehavior = snapFlingBehavior,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(cells.size, key = { index -> cells[index].iso }) { index ->
                    val cell = cells[index]
                    val prevCell = if (index > 0) cells.getOrNull(index - 1) else null
                    val isDayStart = prevCell == null ||
                        cell.iso.substringBefore('T') != prevCell.iso.substringBefore('T')

                    HourlyItem(
                        time = if (index == 0) "Now" else formatStripHour(cell.epochSec, use24Hour),
                        dayLabel = formatDayLabel(cell.epochSec),
                        showDayLabel = isDayStart,
                        precipitationProbability = cell.precip,
                        temperature = cell.temperature,
                        isNow = index == 0,
                        isSelected = selectedIsoTime == cell.iso,
                        condition = weatherConditionForCode(cell.weatherCode),
                        isDay = cell.isDay,
                        tempUnit = tempUnit,
                        onClick = { onHourSelected(cell.iso) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyItem(
    time: String,
    dayLabel: String,
    showDayLabel: Boolean,
    precipitationProbability: Int,
    temperature: Double,
    isNow: Boolean,
    isSelected: Boolean,
    condition: com.vayunmathur.weather.util.WeatherCondition,
    isDay: Boolean,
    tempUnit: TemperatureUnit,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (showDayLabel) {
            Column(
                modifier = Modifier
                    .height(140.dp)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight(0.4f)
                        .alpha(0.2f)
                        .background(MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small)
                )
            }
        }
        Column(
            modifier = Modifier
                .height(140.dp)
                .width(55.dp)
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            TempWithShape(temperature = temperature, tempUnit = tempUnit, highlighted = isNow || isSelected)
            Spacer(Modifier.height(4.dp))
            Text(
                "${precipitationProbability}%",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .alpha(if (precipitationProbability > 0) 1f else 0f),
            )
            WeatherIconBox(icon = condition.iconContent(isDay), size = 32.dp)
            Spacer(Modifier.height(4.dp))
            Text(
                time,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            // Day label removed from here as it's now a section header
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TempWithShape(temperature: Double, tempUnit: TemperatureUnit, highlighted: Boolean) {
    Surface(
        shape = MaterialShapes.Cookie4Sided.toShape(),
        modifier = Modifier.size(38.dp),
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

private fun formatDayLabel(epochSec: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochSeconds(epochSec).toLocalDateTime(tz).date
    return date.dayOfWeek.name.take(3)
}
