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
import com.vayunmathur.photos.data.MaskType
import com.vayunmathur.photos.data.SelectiveMask
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MaskOverlay(
    mask: SelectiveMask,
    showMask: Boolean,
    onMaskChanged: (SelectiveMask) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(mask.type) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                    when (mask.type) {
                        MaskType.Brush -> {
                            onMaskChanged(mask.copy(brushPoints = mask.brushPoints + (x to y)))
                        }
                        MaskType.RadialGradient, MaskType.LinearGradient -> {
                            onMaskChanged(mask.copy(centerX = x, centerY = y))
                        }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val maskColor = Color.Red.copy(alpha = 0.3f)

        if (showMask) {
            when (mask.type) {
                MaskType.Brush -> {
                    val brushPx = mask.brushSize * max(w, h)
                    mask.brushPoints.forEach { (bx, by) ->
                        drawCircle(maskColor, brushPx, Offset(bx * w, by * h))
                    }
                }
                MaskType.RadialGradient -> {
                    val cx = mask.centerX * w
                    val cy = mask.centerY * h
                    val r = mask.radius * max(w, h)
                    drawCircle(maskColor, r, Offset(cx, cy))
                    drawCircle(Color.White.copy(alpha = 0.6f), r, Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, 4.dp.toPx(), Offset(cx, cy))
                }
                MaskType.LinearGradient -> {
                    val cx = mask.centerX * w
                    val cy = mask.centerY * h
                    val r = mask.radius * max(w, h)
                    val rad = Math.toRadians(mask.angle.toDouble())
                    val nx = -sin(rad).toFloat()
                    val ny = cos(rad).toFloat()
                    val extent = max(w, h)
                    val dx = ny * extent
                    val dy = -nx * extent
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(cx + dx - nx * r, cy + dy - ny * r), Offset(cx - dx - nx * r, cy - dy - ny * r), strokeWidth = 1.5.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(cx + dx + nx * r, cy + dy + ny * r), Offset(cx - dx + nx * r, cy - dy + ny * r), strokeWidth = 1.5.dp.toPx())
                    drawCircle(Color.White, 4.dp.toPx(), Offset(cx, cy))
                }
            }
        }
    }
}
