package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.sqrt

enum class StylizeMode { None, FindEdges, Emboss }

data class StylizeParams(
    val mode: StylizeMode = StylizeMode.None,
) {
    fun isIdentity(): Boolean = mode == StylizeMode.None
}

private fun luminanceAt(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
    val sx = x.coerceIn(0, w - 1)
    val sy = y.coerceIn(0, h - 1)
    val p = pixels[sy * w + sx]
    val r = (p ushr 16) and 0xFF
    val g = (p ushr 8) and 0xFF
    val b = p and 0xFF
    return ((r * 299 + g * 587 + b * 114) / 1000)
}

fun StylizeParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val output = IntArray(w * h)

    when (mode) {
        StylizeMode.None -> {
            System.arraycopy(pixels, 0, output, 0, pixels.size)
        }
        StylizeMode.FindEdges -> {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val tl = luminanceAt(pixels, w, h, x - 1, y - 1)
                    val tc = luminanceAt(pixels, w, h, x, y - 1)
                    val tr = luminanceAt(pixels, w, h, x + 1, y - 1)
                    val ml = luminanceAt(pixels, w, h, x - 1, y)
                    val mr = luminanceAt(pixels, w, h, x + 1, y)
                    val bl = luminanceAt(pixels, w, h, x - 1, y + 1)
                    val bc = luminanceAt(pixels, w, h, x, y + 1)
                    val br = luminanceAt(pixels, w, h, x + 1, y + 1)

                    val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                    val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                    val mag = sqrt((gx * gx + gy * gy).toDouble()).toInt().coerceIn(0, 255)
                    val v = 255 - mag

                    val a = (pixels[y * w + x] ushr 24) and 0xFF
                    output[y * w + x] = (a shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        StylizeMode.Emboss -> {
            val kernel = intArrayOf(-2, -1, 0, -1, 1, 1, 0, 1, 2)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0
                    var ki = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            sum += luminanceAt(pixels, w, h, x + dx, y + dy) * kernel[ki]
                            ki++
                        }
                    }
                    val v = (sum + 128).coerceIn(0, 255)
                    val a = (pixels[y * w + x] ushr 24) and 0xFF
                    output[y * w + x] = (a shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(output, 0, w, 0, 0, w, h)
    return result
}
