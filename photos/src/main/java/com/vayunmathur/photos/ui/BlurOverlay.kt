package com.vayunmathur.photos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vayunmathur.photos.data.BlurMode
import com.vayunmathur.photos.data.BlurParams
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun BlurOverlay(
    blurParams: BlurParams,
    onBlurChanged: (BlurParams) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(blurParams.mode) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                    onBlurChanged(blurParams.copy(centerX = x, centerY = y))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val cx = blurParams.centerX * w
        val cy = blurParams.centerY * h
        val guideColor = Color.White.copy(alpha = 0.6f)
        val guideStroke = Stroke(width = 1.5.dp.toPx())

        when (blurParams.mode) {
            BlurMode.Radial, BlurMode.Lens -> {
                val r = blurParams.radius * max(w, h)
                drawCircle(guideColor, r, Offset(cx, cy), style = guideStroke)
                drawCircle(Color.White, 4.dp.toPx(), Offset(cx, cy))
            }
            BlurMode.Linear -> {
                val r = blurParams.radius * max(w, h)
                val rad = Math.toRadians(blurParams.angle.toDouble())
                val nx = -sin(rad).toFloat()
                val ny = cos(rad).toFloat()
                val extent = max(w, h)
                val dx = ny * extent
                val dy = -nx * extent
                drawLine(guideColor, Offset(cx + dx - nx * r, cy + dy - ny * r), Offset(cx - dx - nx * r, cy - dy - ny * r), strokeWidth = guideStroke.width)
                drawLine(guideColor, Offset(cx + dx + nx * r, cy + dy + ny * r), Offset(cx - dx + nx * r, cy - dy + ny * r), strokeWidth = guideStroke.width)
                drawCircle(Color.White, 4.dp.toPx(), Offset(cx, cy))
            }
        }
    }
}
