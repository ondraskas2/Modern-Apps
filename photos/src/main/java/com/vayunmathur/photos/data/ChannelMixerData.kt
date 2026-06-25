package com.vayunmathur.photos.data

import android.graphics.Bitmap

data class ChannelMixerAdjustment(
    val rFromR: Float = 1f,
    val rFromG: Float = 0f,
    val rFromB: Float = 0f,
    val gFromR: Float = 0f,
    val gFromG: Float = 1f,
    val gFromB: Float = 0f,
    val bFromR: Float = 0f,
    val bFromG: Float = 0f,
    val bFromB: Float = 1f,
    val monochrome: Boolean = false,
) {
    fun isIdentity(): Boolean =
        !monochrome &&
            rFromR == 1f && rFromG == 0f && rFromB == 0f &&
            gFromR == 0f && gFromG == 1f && gFromB == 0f &&
            bFromR == 0f && bFromG == 0f && bFromB == 1f
}

fun ChannelMixerAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF

        val nr: Int
        val ng: Int
        val nb: Int
        if (monochrome) {
            val gray = (rFromR * r + rFromG * g + rFromB * b).toInt().coerceIn(0, 255)
            nr = gray
            ng = gray
            nb = gray
        } else {
            nr = (rFromR * r + rFromG * g + rFromB * b).toInt().coerceIn(0, 255)
            ng = (gFromR * r + gFromG * g + gFromB * b).toInt().coerceIn(0, 255)
            nb = (bFromR * r + bFromG * g + bFromB * b).toInt().coerceIn(0, 255)
        }

        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
