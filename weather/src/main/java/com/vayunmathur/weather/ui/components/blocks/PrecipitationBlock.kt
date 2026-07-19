package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconRain
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.util.mmToInches
import java.util.Locale

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
        val inches = mmToInches(amountMm ?: 0.0)
        String.format(Locale.US, "%.2f", inches) to "in"
    } else {
        val mm = amountMm ?: 0.0
        (if (mm < 10) String.format(Locale.US, "%.1f", mm) else mm.toInt().toString()) to "mm"
    }

    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconRain(m, c) }, title = "Precipitation")
        }
        Column(
            modifier = Modifier.align(Alignment.Center).offset(y = if (nowcast != null) (-12).dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = if (nowcast != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (nowcast != null) {
            Text(
                text = nowcast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
