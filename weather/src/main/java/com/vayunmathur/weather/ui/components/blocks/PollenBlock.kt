package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconGrass
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.AirQualityCurrent

/**
 * Pollen block — added beyond WeatherMaster's set since we already fetch
 * pollen counts from the Open-Meteo Air Quality endpoint. Same square
 * `extraLarge` shell as the other simple blocks.
 */
@Composable
fun PollenBlock(air: AirQualityCurrent?) {
    val worst = listOfNotNull(
        air?.grassPollen,
        air?.alderPollen,
        air?.birchPollen,
        air?.olivePollen,
        air?.ragweedPollen,
        air?.mugwortPollen,
    ).maxOrNull() ?: 0.0
    val level = when {
        worst <= 0.0 -> 0
        worst < 10 -> 1
        worst < 50 -> 2
        worst < 200 -> 3
        else -> 4
    }
    val label = when (level) {
        0 -> "None"
        1 -> "Low"
        2 -> "Medium"
        3 -> "High"
        else -> "Severe"
    }
    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconGrass(m, c) }, title = "Pollen")
        }
        Text(
            text = "$level/4",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.align(Alignment.Center).offset(y = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-20).dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
