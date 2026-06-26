package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.formatTemperatureCompact

/**
 * Port of WeatherMaster's `HumidityBlock`. Square `extraLarge` Surface.
 * Water-level wave drawable (one of 5 variants based on humidity) tinted
 * with `inversePrimary` fills the bottom of the card. Header top-start,
 * big % value center-start, dew-point pill bottom-start.
 */
@Composable
fun HumidityBlock(current: Current, tempUnit: TemperatureUnit) {
    val humidity = current.relativeHumidity
    val waveDrawable = when (humidity) {
        in 0..30 -> R.drawable.humidity_seven_percent
        in 30..50 -> R.drawable.humidity_thirty_precent
        in 50..70 -> R.drawable.humidity_fifty_percent
        in 70..90 -> R.drawable.humidity_seventy_percent
        else -> R.drawable.humidity_ninety_percent
    }
    val tint = MaterialTheme.colorScheme.inversePrimary

    SquareBlock {
        Image(
            painter = painterResource(id = waveDrawable),
            contentDescription = null,
            modifier = Modifier.matchParentSize().align(Alignment.BottomCenter),
            alignment = Alignment.BottomCenter,
            colorFilter = ColorFilter.tint(tint),
        )
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(iconRes = R.drawable.outline_drizzle_24, title = "Humidity")
        }
        Text(
            text = "${humidity}%",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(Modifier.align(Alignment.BottomStart)) {
            DewPointRow(dewPointCelsius = current.dewPoint, tempUnit = tempUnit)
        }
    }
}

@Composable
private fun DewPointRow(dewPointCelsius: Double, tempUnit: TemperatureUnit) {
    Row(
        modifier = Modifier.padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                Text(
                    formatTemperatureCompact(dewPointCelsius, tempUnit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            "Dew point",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
