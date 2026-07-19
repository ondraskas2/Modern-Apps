package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.IconSunny
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Port of WeatherMaster's `UvIndexBlock`. Standard square block with the
 * 5 colored severity dots arranged in a horizontal line below the UV value.
 * The active level's dot renders larger and at full alpha; the others are
 * small and faded.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UvIndexBlock(uvIndex: Double?) {
    val v = uvIndex?.roundToInt()
    val activeLevel: Int = when {
        v == null -> -1
        v < 3 -> 0      // Low
        v < 6 -> 1      // Moderate
        v < 8 -> 2      // High
        v < 11 -> 3     // Very high
        else -> 4       // Extreme
    }
    val label = when (activeLevel) {
        -1 -> "—"
        0 -> "Low"
        1 -> "Moderate"
        2 -> "High"
        3 -> "Very high"
        else -> "Extreme"
    }
    val dotColors = listOf(
        Color(0xFF6DD58C), // Low — green
        Color(0xFFFCC934), // Moderate — yellow
        Color(0xFFFA903E), // High — orange
        Color(0xFFAF5CF7), // Very high — purple
        Color(0xFFEE675C), // Extreme — red
    )

    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(
                icon = { m, c -> IconSunny(m, c) },
                title = "UV index",
            )
        }
        
        Text(
            text = v?.toString() ?: "—",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.align(Alignment.Center).offset(y = (-10).dp),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Linear severity dots
        Row(
            modifier = Modifier.align(Alignment.Center).offset(y = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dotColors.forEachIndexed { i, color ->
                Canvas(modifier = Modifier.size(if (i == activeLevel) 12.dp else 8.dp)) {
                    drawCircle(
                        color = color,
                        alpha = if (i == activeLevel) 1f else 0.25f
                    )
                }
            }
        }

        Text(
            text = label,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-14).dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
