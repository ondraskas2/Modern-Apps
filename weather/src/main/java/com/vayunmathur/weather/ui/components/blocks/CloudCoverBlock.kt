package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current

/**
 * Cloud-cover percentage for the resolved period. Circular surface matching
 * the wind/pressure/visibility blocks, with a descriptive label
 * ("Clear" … "Overcast") under the value.
 */
@Composable
fun CloudCoverBlock(current: Current) {
    val pct = current.cloudCover.coerceIn(0, 100)
    val label = when {
        pct < 10 -> "Clear"
        pct < 40 -> "Mostly clear"
        pct < 70 -> "Partly cloudy"
        pct < 90 -> "Mostly cloudy"
        else -> "Overcast"
    }
    CircularStatBlock {
        Box(Modifier.align(Alignment.TopCenter)) {
            BlockHeader(
                iconRes = R.drawable.outline_cloudy_24,
                title = "Cloud cover",
                topPadding = 36.dp,
            )
        }
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.align(Alignment.Center).offset(y = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-26).dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
