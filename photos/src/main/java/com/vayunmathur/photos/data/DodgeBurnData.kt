package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class DodgeBurnMode { Dodge, Burn }

enum class TonalRange { Shadows, Midtones, Highlights }

data class DodgeBurnStroke(
    val points: List<Pair<Float, Float>>,
    val mode: DodgeBurnMode,
    val range: TonalRange = TonalRange.Midtones,
    val exposure: Float = 0.5f,
    val brushSize: Float = 0.05f,
)

data class DodgeBurnStrokes(
    val strokes: List<DodgeBurnStroke> = emptyList(),
) {
    fun isIdentity(): Boolean = strokes.isEmpty()
}

fun DodgeBurnStrokes.applyToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)
    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        val brushPx = (stroke.brushSize * max(w, h)).coerceAtLeast(1f)
        val brushR = brushPx.toInt().coerceAtLeast(1)
        val sign = if (stroke.mode == DodgeBurnMode.Dodge) 1f else -1f
        for ((px, py) in stroke.points) {
            val cx = (px * w).toInt()
            val cy = (py * h).toInt()
            for (dy in -brushR..brushR) {
                for (dx in -brushR..brushR) {
                    val dist = sqrt((dx * dx + dy * dy).toFloat())
                    if (dist > brushPx) continue
                    val feather = (1f - dist / brushPx).coerceIn(0f, 1f)
                    val tx = (cx + dx).coerceIn(0, w - 1)
                    val ty = (cy + dy).coerceIn(0, h - 1)
                    val idx = ty * w + tx
                    val pixel = pixels[idx]
                    val a = (pixel shr 24) and 0xFF
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    val rangeWeight = when (stroke.range) {
                        TonalRange.Shadows -> 1f - l
                        TonalRange.Highlights -> l
                        TonalRange.Midtones -> 1f - abs(2f * l - 1f)
                    }
                    val factor = 1f + sign * stroke.exposure * feather * rangeWeight
                    val nr = (r * factor).toInt().coerceIn(0, 255)
                    val ng = (g * factor).toInt().coerceIn(0, 255)
                    val nb = (b * factor).toInt().coerceIn(0, 255)
                    pixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
    }
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
