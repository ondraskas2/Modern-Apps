package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class UnsharpMask(
    val amount: Float = 0f,
    val radius: Float = 2f,
    val threshold: Int = 0,
) {
    fun isIdentity(): Boolean = amount == 0f
}

private fun unsharpGaussianKernel(radius: Int): FloatArray {
    val size = radius * 2 + 1
    val kernel = FloatArray(size)
    val sigma = radius / 3f
    var sum = 0f
    for (i in 0 until size) {
        val x = (i - radius).toFloat()
        kernel[i] = exp(-(x * x) / (2f * sigma * sigma))
        sum += kernel[i]
    }
    for (i in 0 until size) kernel[i] /= sum
    return kernel
}

private fun unsharpGaussianBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0) return pixels.copyOf()
    val kernel = unsharpGaussianKernel(radius)
    val temp = IntArray(w * h)
    val output = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rr = 0f; var gg = 0f; var bb = 0f; var aa = 0f
            for (k in -radius..radius) {
                val sx = (x + k).coerceIn(0, w - 1)
                val px = pixels[y * w + sx]
                val weight = kernel[k + radius]
                aa += ((px ushr 24) and 0xFF) * weight
                rr += ((px ushr 16) and 0xFF) * weight
                gg += ((px ushr 8) and 0xFF) * weight
                bb += (px and 0xFF) * weight
            }
            temp[y * w + x] = (aa.toInt().coerceIn(0, 255) shl 24) or
                (rr.toInt().coerceIn(0, 255) shl 16) or
                (gg.toInt().coerceIn(0, 255) shl 8) or
                bb.toInt().coerceIn(0, 255)
        }
    }
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rr = 0f; var gg = 0f; var bb = 0f; var aa = 0f
            for (k in -radius..radius) {
                val sy = (y + k).coerceIn(0, h - 1)
                val px = temp[sy * w + x]
                val weight = kernel[k + radius]
                aa += ((px ushr 24) and 0xFF) * weight
                rr += ((px ushr 16) and 0xFF) * weight
                gg += ((px ushr 8) and 0xFF) * weight
                bb += (px and 0xFF) * weight
            }
            output[y * w + x] = (aa.toInt().coerceIn(0, 255) shl 24) or
                (rr.toInt().coerceIn(0, 255) shl 16) or
                (gg.toInt().coerceIn(0, 255) shl 8) or
                bb.toInt().coerceIn(0, 255)
        }
    }
    return output
}

fun UnsharpMask.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val strength = amount / 100f
    val intRadius = radius.roundToInt().coerceAtLeast(1)
    val blurred = unsharpGaussianBlur(pixels, w, h, intRadius)
    val output = IntArray(w * h)

    for (i in pixels.indices) {
        val p = pixels[i]
        val bp = blurred[i]
        val a = (p ushr 24) and 0xFF
        val origR = (p ushr 16) and 0xFF
        val origG = (p ushr 8) and 0xFF
        val origB = p and 0xFF
        val blurR = (bp ushr 16) and 0xFF
        val blurG = (bp ushr 8) and 0xFF
        val blurB = bp and 0xFF

        val r = sharpenChannel(origR, blurR, strength, threshold)
        val g = sharpenChannel(origG, blurG, strength, threshold)
        val b = sharpenChannel(origB, blurB, strength, threshold)

        output[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(output, 0, w, 0, 0, w, h)
    return result
}

private fun sharpenChannel(orig: Int, blurred: Int, strength: Float, threshold: Int): Int {
    val diff = orig - blurred
    return if (abs(diff) > threshold) {
        (orig + strength * diff).toInt().coerceIn(0, 255)
    } else {
        orig
    }
}
