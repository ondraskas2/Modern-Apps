package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.pow

data class LevelsAdjustment(
    val inBlack: Float = 0f,
    val inWhite: Float = 255f,
    val gamma: Float = 1f,
    val outBlack: Float = 0f,
    val outWhite: Float = 255f,
) {
    fun isIdentity(): Boolean =
        inBlack == 0f && inWhite == 255f && gamma == 1f && outBlack == 0f && outWhite == 255f
}

fun LevelsAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val lut = IntArray(256)
    val range = (inWhite - inBlack).coerceAtLeast(1f)
    val invGamma = 1f / gamma.coerceAtLeast(0.01f)
    for (v in 0..255) {
        var n = ((v - inBlack) / range).coerceIn(0f, 1f)
        n = n.pow(invGamma)
        lut[v] = (outBlack + n * (outWhite - outBlack)).toInt().coerceIn(0, 255)
    }

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val nr = lut[r]
        val ng = lut[g]
        val nb = lut[b]
        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
