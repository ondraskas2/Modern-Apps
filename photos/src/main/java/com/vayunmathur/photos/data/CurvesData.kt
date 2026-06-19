package com.vayunmathur.photos.data

import android.graphics.Bitmap
import android.graphics.ColorMatrix

data class CurveControlPoint(val x: Float, val y: Float)

enum class CurveChannel { Combined, Red, Green, Blue }

data class CurvesAdjustment(
    val combined: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
    val red: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
    val green: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
    val blue: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
) {
    fun isIdentity(): Boolean =
        combined.size == 2 && combined[0] == CurveControlPoint(0f, 0f) && combined[1] == CurveControlPoint(1f, 1f) &&
        red.size == 2 && red[0] == CurveControlPoint(0f, 0f) && red[1] == CurveControlPoint(1f, 1f) &&
        green.size == 2 && green[0] == CurveControlPoint(0f, 0f) && green[1] == CurveControlPoint(1f, 1f) &&
        blue.size == 2 && blue[0] == CurveControlPoint(0f, 0f) && blue[1] == CurveControlPoint(1f, 1f)
}

fun interpolateSpline(points: List<CurveControlPoint>, steps: Int = 256): FloatArray {
    val sorted = points.sortedBy { it.x }
    val lut = FloatArray(steps)
    if (sorted.size < 2) {
        for (i in 0 until steps) lut[i] = i.toFloat() / (steps - 1)
        return lut
    }
    for (i in 0 until steps) {
        val t = i.toFloat() / (steps - 1)
        val idx = sorted.indexOfLast { it.x <= t }.coerceAtLeast(0)
        val p0 = sorted[idx]
        val p1 = sorted[(idx + 1).coerceAtMost(sorted.lastIndex)]
        val frac = if (p1.x == p0.x) 0f else ((t - p0.x) / (p1.x - p0.x)).coerceIn(0f, 1f)
        val smoothFrac = frac * frac * (3f - 2f * frac)
        lut[i] = (p0.y + (p1.y - p0.y) * smoothFrac).coerceIn(0f, 1f)
    }
    return lut
}

fun CurvesAdjustment.generateLuts(): Array<FloatArray> = arrayOf(
    interpolateSpline(combined),
    interpolateSpline(red),
    interpolateSpline(green),
    interpolateSpline(blue),
)

fun CurvesAdjustment.toColorMatrix(): ColorMatrix {
    val mid = interpolateSpline(combined, 3)
    val rMid = interpolateSpline(red, 3)
    val gMid = interpolateSpline(green, 3)
    val bMid = interpolateSpline(blue, 3)
    val combinedScale = mid[2] - mid[0]
    val combinedOff = mid[0]
    val rChScale = rMid[2] - rMid[0]
    val gChScale = gMid[2] - gMid[0]
    val bChScale = bMid[2] - bMid[0]
    val rScale = combinedScale * rChScale
    val gScale = combinedScale * gChScale
    val bScale = combinedScale * bChScale
    val rOffset = (rChScale * combinedOff + rMid[0]) * 255f
    val gOffset = (gChScale * combinedOff + gMid[0]) * 255f
    val bOffset = (bChScale * combinedOff + bMid[0]) * 255f
    return ColorMatrix(floatArrayOf(
        rScale, 0f, 0f, 0f, rOffset,
        0f, gScale, 0f, 0f, gOffset,
        0f, 0f, bScale, 0f, bOffset,
        0f, 0f, 0f, 1f, 0f,
    ))
}

fun CurvesAdjustment.applyLutToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val luts = generateLuts()
    val combinedLut = luts[0]
    val rLut = luts[1]
    val gLut = luts[2]
    val bLut = luts[3]
    for (i in pixels.indices) {
        val a = (pixels[i] shr 24) and 0xFF
        var r = (pixels[i] shr 16) and 0xFF
        var g = (pixels[i] shr 8) and 0xFF
        var b = pixels[i] and 0xFF
        r = (combinedLut[r] * 255f).toInt().coerceIn(0, 255)
        g = (combinedLut[g] * 255f).toInt().coerceIn(0, 255)
        b = (combinedLut[b] * 255f).toInt().coerceIn(0, 255)
        r = (rLut[r] * 255f).toInt().coerceIn(0, 255)
        g = (gLut[g] * 255f).toInt().coerceIn(0, 255)
        b = (bLut[b] * 255f).toInt().coerceIn(0, 255)
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
