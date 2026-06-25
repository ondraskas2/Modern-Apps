package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.abs

data class ColorBalanceAdjustment(
    val shadowsRedCyan: Float = 0f,
    val shadowsGreenMagenta: Float = 0f,
    val shadowsBlueYellow: Float = 0f,
    val midRedCyan: Float = 0f,
    val midGreenMagenta: Float = 0f,
    val midBlueYellow: Float = 0f,
    val highRedCyan: Float = 0f,
    val highGreenMagenta: Float = 0f,
    val highBlueYellow: Float = 0f,
    val preserveLuminosity: Boolean = true,
) {
    fun isIdentity(): Boolean =
        shadowsRedCyan == 0f && shadowsGreenMagenta == 0f && shadowsBlueYellow == 0f &&
            midRedCyan == 0f && midGreenMagenta == 0f && midBlueYellow == 0f &&
            highRedCyan == 0f && highGreenMagenta == 0f && highBlueYellow == 0f
}

fun ColorBalanceAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val scale = 0.6f

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF

        val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

        val wS = (1f - l).coerceIn(0f, 1f)
        val wH = l.coerceIn(0f, 1f)
        val wM = (1f - abs(2f * l - 1f)).coerceIn(0f, 1f)

        var nr = r + (shadowsRedCyan * wS + midRedCyan * wM + highRedCyan * wH) * scale
        var ng = g + (shadowsGreenMagenta * wS + midGreenMagenta * wM + highGreenMagenta * wH) * scale
        var nb = b + (shadowsBlueYellow * wS + midBlueYellow * wM + highBlueYellow * wH) * scale

        if (preserveLuminosity) {
            val origL = 0.299f * r + 0.587f * g + 0.114f * b
            val newL = 0.299f * nr + 0.587f * ng + 0.114f * nb
            val diff = origL - newL
            nr += diff
            ng += diff
            nb += diff
        }

        val ir = nr.toInt().coerceIn(0, 255)
        val ig = ng.toInt().coerceIn(0, 255)
        val ib = nb.toInt().coerceIn(0, 255)
        pixels[i] = (a shl 24) or (ir shl 16) or (ig shl 8) or ib
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
