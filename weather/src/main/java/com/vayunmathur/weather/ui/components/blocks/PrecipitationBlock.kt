package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R

/**
 * Precipitation amount for the resolved period (today / a selected day's
 * total, or a selected hour's amount) plus an optional short-range nowcast
 * subtitle derived from the 15-minute series. Square `extraLarge` surface to
 * match the other blocks.
 */
@Composable
fun PrecipitationBlock(
    amountMm: Double?,
    useInches: Boolean,
    nowcast: String?,
) {
    val (value, unit) = if (useInches) {
        val inches = (amountMm ?: 0.0) / 25.4
        String.format("%.2f", inches) to "in"
    } else {
        val mm = amountMm ?: 0.0
        (if (mm < 10) String.format("%.1f", mm) else mm.toInt().toString()) to "mm"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().aspectRatio(1f)) {
            Box(Modifier.align(Alignment.TopStart)) {
                BlockHeader(iconRes = R.drawable.outline_rain_24, title = "Precipitation")
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (nowcast != null) {
                Text(
                    text = nowcast,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
