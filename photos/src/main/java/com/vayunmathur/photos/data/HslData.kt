package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class HslColorRange(val label: String, val hueCenter: Float) {
    Red("Red", 0f),
    Orange("Orange", 30f),
    Yellow("Yellow", 60f),
    Green("Green", 120f),
    Cyan("Cyan", 180f),
    Blue("Blue", 240f),
    Purple("Purple", 270f),
    Magenta("Magenta", 300f),
}

data class HslChannelAdjustment(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
)

data class HslAdjustments(
    val channels: Map<HslColorRange, HslChannelAdjustment> = HslColorRange.entries.associateWith { HslChannelAdjustment() },
) {
    fun isIdentity(): Boolean = channels.values.all { it.hue == 0f && it.saturation == 0f && it.luminance == 0f }
}

fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val cMax = max(rf, max(gf, bf))
    val cMin = min(rf, min(gf, bf))
    val delta = cMax - cMin
    val l = (cMax + cMin) / 2f
    if (delta == 0f) return floatArrayOf(0f, 0f, l)
    val s = if (l < 0.5f) delta / (cMax + cMin) else delta / (2f - cMax - cMin)
    val h = when (cMax) {
        rf -> ((gf - bf) / delta + (if (gf < bf) 6f else 0f)) * 60f
        gf -> ((bf - rf) / delta + 2f) * 60f
        else -> ((rf - gf) / delta + 4f) * 60f
    }
    return floatArrayOf(h, s, l)
}

fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
    if (s == 0f) {
        val v = (l * 255f).toInt().coerceIn(0, 255)
        return intArrayOf(v, v, v)
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun hue2rgb(t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
    val hNorm = h / 360f
    return intArrayOf(
        (hue2rgb(hNorm + 1f / 3f) * 255f).toInt().coerceIn(0, 255),
        (hue2rgb(hNorm) * 255f).toInt().coerceIn(0, 255),
        (hue2rgb(hNorm - 1f / 3f) * 255f).toInt().coerceIn(0, 255),
    )
}

private fun hueWeight(pixelHue: Float, centerHue: Float): Float {
    val diff = abs(((pixelHue - centerHue + 180f + 360f) % 360f) - 180f)
    return (1f - (diff / 30f)).coerceIn(0f, 1f)
}

fun HslAdjustments.applyHslToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val a = (pixels[i] shr 24) and 0xFF
        val r = (pixels[i] shr 16) and 0xFF
        val g = (pixels[i] shr 8) and 0xFF
        val b = pixels[i] and 0xFF
        val hsl = rgbToHsl(r, g, b)
        var hue = hsl[0]
        var sat = hsl[1]
        var lum = hsl[2]
        for ((range, adj) in channels) {
            val weight = hueWeight(hue, range.hueCenter)
            if (weight > 0f) {
                hue = (hue + adj.hue * weight) % 360f
                if (hue < 0f) hue += 360f
                sat = (sat + adj.saturation / 100f * weight).coerceIn(0f, 1f)
                lum = (lum + adj.luminance / 100f * weight).coerceIn(0f, 1f)
            }
        }
        val rgb = hslToRgb(hue, sat, lum)
        pixels[i] = (a shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
