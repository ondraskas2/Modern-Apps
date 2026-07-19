package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.IconClearNight
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconSunny
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.util.formatClockTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct port of WeatherMaster's `SunBlock` minus the background SVG (we
 * canvas-draw the arc directly). Square `extraLarge` surface with a curved
 * sun-track arc + sun marker positioned by today's elapsed-daylight
 * progress, and a translucent bottom panel with sunrise / sunset times.
 */
@Composable
fun SunBlock(sunriseEpochSec: Long?, sunsetEpochSec: Long?, use24Hour: Boolean, daylightDurationSec: Double? = null) {
    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconSunny(m, c) }, title = "Sun")
        }

        val arcColor = MaterialTheme.colorScheme.tertiaryContainer
        val sunColor = MaterialTheme.colorScheme.primary
        val now = System.currentTimeMillis() / 1000

        val progress: Float = if (sunriseEpochSec != null && sunsetEpochSec != null && sunsetEpochSec > sunriseEpochSec) {
            ((now - sunriseEpochSec).toDouble() / (sunsetEpochSec - sunriseEpochSec))
                .coerceIn(0.0, 1.0).toFloat()
        } else 0f

        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            val w = size.width
            val h = size.height
            // Half-circle arc from the bottom edge.
            val cx = w / 2f
            val cy = h * 0.65f
            val r = w * 0.4f
            drawArc(
                color = arcColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                ),
            )
            // Sun marker.
            val theta = Math.toRadians(180 + 180.0 * progress)
            val sx = (cx + r * cos(theta)).toFloat()
            val sy = (cy + r * sin(theta)).toFloat()
            drawCircle(color = sunColor, radius = 6.dp.toPx(), center = Offset(sx, sy))
        }

        // Bottom panel with sunrise / sunset times + daylight length.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.42f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RiseSetTimeRow(
                        text = sunriseEpochSec?.let { formatClockTime(it, use24Hour) } ?: "—",
                        icon = { m, c -> IconSunny(m, c) },
                    )
                    RiseSetTimeRow(
                        text = sunsetEpochSec?.let { formatClockTime(it, use24Hour) } ?: "—",
                        icon = { m, c -> IconClearNight(m, c) },
                    )
                    if (daylightDurationSec != null) {
                        RiseSetTimeRow(
                            text = formatDuration(daylightDurationSec),
                            icon = { m, c -> IconSchedule(m, c) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RiseSetTimeRow(text: String, icon: @Composable (Modifier, Color) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        icon(Modifier.size(16.dp), MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return "${h}h ${m}m"
}
