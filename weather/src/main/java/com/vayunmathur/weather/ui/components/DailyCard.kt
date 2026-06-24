package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.formatTemperatureCompact
import com.vayunmathur.weather.util.weatherConditionForCode
import kotlinx.datetime.LocalDate

/**
 * Direct port of WeatherMaster's `DailyCard`. `Surface(extraLarge, surface,
 * shadowElevation = 2.dp)` containing [CardsHeader] + a `LazyRow` of
 * 210 dp × 65 dp pill `DailyItem`s with 6 dp spacing. Each item shows
 * max/min temps on top, weather icon + precip% + weekday on the bottom.
 */
@Composable
fun DailyCard(
    daily: Daily,
    tempUnit: TemperatureUnit,
    selectedIsoDate: String? = null,
    onDaySelected: (String) -> Unit = {},
) {
    if (daily.time.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            CardsHeader(text = "Daily forecast", iconRes = R.drawable.outline_calendar_24)
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(daily.time.size, key = { "${daily.time[it]}_$it" }) { index ->
                    val date = daily.time.getOrNull(index)
                    val hi = daily.temperatureMax.getOrNull(index) ?: 0.0
                    val lo = daily.temperatureMin.getOrNull(index) ?: 0.0
                    val code = daily.weatherCode.getOrNull(index) ?: 0
                    val precip = daily.precipitationProbabilityMax.getOrNull(index) ?: 0

                    if (index == 0) Spacer(Modifier.width(16.dp))

                    DailyItem(
                        weekday = if (index == 0) "Today" else dayLabel(date),
                        maxTemp = hi,
                        minTemp = lo,
                        iconRes = weatherConditionForCode(code).iconRes(true),
                        precipitationProbability = precip,
                        tempUnit = tempUnit,
                        isSelected = date != null && date == selectedIsoDate,
                        onClick = { if (date != null) onDaySelected(date) },
                    )

                    if (index == daily.time.size - 1) Spacer(Modifier.width(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DailyItem(
    weekday: String,
    maxTemp: Double,
    minTemp: Double,
    iconRes: Int,
    precipitationProbability: Int,
    tempUnit: TemperatureUnit,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = CircleShape,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .height(210.dp)
                .width(65.dp)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatTemperatureCompact(maxTemp, tempUnit),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    formatTemperatureCompact(minTemp, tempUnit),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WeatherIconBox(iconRes = iconRes, size = 38.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${precipitationProbability}%",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    weekday,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun dayLabel(dateStr: String?): String {
    if (dateStr == null) return "-"
    val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return dateStr
    return date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}
