package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.util.SelectedDateOrTime
import com.vayunmathur.weather.util.formatDayMonthLabel
import com.vayunmathur.weather.util.formatSelectedHourLabel
import com.vayunmathur.library.R as LibraryR

/**
 * Prominent banner shown at the top of the page when the user is inspecting a
 * specific hour or day. Displays a formatted label and a clear (X) button
 * that returns to the live "now / today" view.
 */
@Composable
fun SelectedDateTimeHeader(
    selection: SelectedDateOrTime,
    forecast: ForecastResponse,
    use24Hour: Boolean,
    onClear: () -> Unit,
) {
    val label = when (selection) {
        is SelectedDateOrTime.Time -> formatSelectedHourLabel(selection.isoTime, use24Hour)
        is SelectedDateOrTime.Day -> formatDayMonthLabel(selection.isoDate)
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            IconButton(onClick = onClear) {
                Icon(
                    painter = painterResource(LibraryR.drawable.close_24px),
                    contentDescription = "Clear selection",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
