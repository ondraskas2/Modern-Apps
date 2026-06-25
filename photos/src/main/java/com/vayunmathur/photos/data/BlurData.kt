package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class BlurMode { Radial, Linear, Lens }

data class BlurParams(
    val mode: BlurMode = BlurMode.Radial,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.3f,
    val intensity: Float = 10f,
    val feather: Float = 0.3f,
    val angle: Float = 0f,
) {
    fun isIdentity(): Boolean = intensity == 0f
}

private fun generateGaussianKernel(radius: Int): FloatArray {
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

internal fun gaussianBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0) return pixels.copyOf()
    val kernel = generateGaussianKernel(radius)
    val temp = IntArray(w * h)
    val output = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rr = 0f; var gg = 0f; var bb = 0f; var aa = 0f
            for (k in -radius..radius) {
                val sx = (x + k).coerceIn(0, w - 1)
                val px = pixels[y * w + sx]
                val weight = kernel[k + radius]
                aa += ((px shr 24) and 0xFF) * weight
                rr += ((px shr 16) and 0xFF) * weight
                gg += ((px shr 8) and 0xFF) * weight
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
                aa += ((px shr 24) and 0xFF) * weight
                rr += ((px shr 16) and 0xFF) * weight
                gg += ((px shr 8) and 0xFF) * weight
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

private fun generateMask(params: BlurParams, w: Int, h: Int): FloatArray {
    val mask = FloatArray(w * h)
    when (params.mode) {
        BlurMode.Radial -> {
            val cx = params.centerX * w
            val cy = params.centerY * h
            val r = params.radius * max(w, h)
            val featherDist = params.feather * max(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
                    mask[y * w + x] = if (dist < r) 0f
                    else ((dist - r) / featherDist.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
        }
        BlurMode.Linear -> {
            val cx = params.centerX * w
            val cy = params.centerY * h
            val r = params.radius * max(w, h)
            val featherDist = params.feather * max(w, h)
            val rad = Math.toRadians(params.angle.toDouble())
            val nx = -kotlin.math.sin(rad).toFloat()
            val ny = kotlin.math.cos(rad).toFloat()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = kotlin.math.abs((x - cx) * nx + (y - cy) * ny)
                    mask[y * w + x] = if (dist < r) 0f
                    else ((dist - r) / featherDist.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
        }
        BlurMode.Lens -> {
            val cx = params.centerX * w
            val cy = params.centerY * h
            val r = params.radius * max(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
                    mask[y * w + x] = if (dist > r) 1f else 0f
                }
            }
        }
    }
    return mask
}

fun BlurParams.applyBlurToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val blurRadius = (intensity * 0.5f).toInt().coerceIn(1, 25)
    val blurred = gaussianBlur(pixels, w, h, blurRadius)
    val mask = generateMask(this, w, h)
    val output = IntArray(w * h)
    for (i in pixels.indices) {
        val m = mask[i]
        val origA = (pixels[i] shr 24) and 0xFF
        val origR = (pixels[i] shr 16) and 0xFF
        val origG = (pixels[i] shr 8) and 0xFF
        val origB = pixels[i] and 0xFF
        val blurA = (blurred[i] shr 24) and 0xFF
        val blurR = (blurred[i] shr 16) and 0xFF
        val blurG = (blurred[i] shr 8) and 0xFF
        val blurB = blurred[i] and 0xFF
        val a = (origA + (blurA - origA) * m).toInt().coerceIn(0, 255)
        val r = (origR + (blurR - origR) * m).toInt().coerceIn(0, 255)
        val g = (origG + (blurG - origG) * m).toInt().coerceIn(0, 255)
        val b = (origB + (blurB - origB) * m).toInt().coerceIn(0, 255)
        output[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(output, 0, w, 0, 0, w, h)
    return result
}

// --- Phase 4: full-image filter blurs -------------------------------------------

enum class FilterBlurMode { Gaussian, Motion, Radial, Spin }

/** Full-image blur filter (destructive), distinct from the masked [BlurParams] lens blur. */
data class FilterBlur(
    val mode: FilterBlurMode = FilterBlurMode.Gaussian,
    val amount: Float = 0f,
    val angle: Float = 0f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    fun isIdentity(): Boolean = amount == 0f
}

fun FilterBlur.applyToBitmap(bitmap: Bitmap): Bitmap {
    if (isIdentity()) return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)
    val out = when (mode) {
        FilterBlurMode.Gaussian -> gaussianBlur(src, w, h, (amount * 0.5f).toInt().coerceIn(1, 60))
        FilterBlurMode.Motion -> motionBlur(src, w, h, amount.toInt().coerceIn(1, 100), angle)
        FilterBlurMode.Radial -> radialBlur(src, w, h, amount, centerX, centerY, spin = false)
        FilterBlurMode.Spin -> radialBlur(src, w, h, amount, centerX, centerY, spin = true)
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, w, 0, 0, w, h)
    return result
}

private fun motionBlur(src: IntArray, w: Int, h: Int, length: Int, angle: Float): IntArray {
    val out = IntArray(w * h)
    val rad = Math.toRadians(angle.toDouble())
    val dx = kotlin.math.cos(rad).toFloat()
    val dy = kotlin.math.sin(rad).toFloat()
    val half = length / 2
    for (y in 0 until h) {
        for (x in 0 until w) {
            var aa = 0f; var rr = 0f; var gg = 0f; var bb = 0f; var n = 0f
            for (k in -half..half) {
                val sx = (x + (dx * k)).toInt().coerceIn(0, w - 1)
                val sy = (y + (dy * k)).toInt().coerceIn(0, h - 1)
                val p = src[sy * w + sx]
                aa += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                gg += (p ushr 8) and 0xFF
                bb += p and 0xFF
                n++
            }
            out[y * w + x] = ((aa / n).toInt() shl 24) or ((rr / n).toInt() shl 16) or
                ((gg / n).toInt() shl 8) or (bb / n).toInt()
        }
    }
    return out
}

private fun radialBlur(
    src: IntArray, w: Int, h: Int, amount: Float,
    centerX: Float, centerY: Float, spin: Boolean,
): IntArray {
    val out = IntArray(w * h)
    val cx = centerX * w
    val cy = centerY * h
    val samples = (amount * 0.2f).toInt().coerceIn(3, 30)
    val strength = amount / 100f
    for (y in 0 until h) {
        for (x in 0 until w) {
            var aa = 0f; var rr = 0f; var gg = 0f; var bb = 0f
            val odx = x - cx
            val ody = y - cy
            for (s in 0 until samples) {
                val t = s.toFloat() / samples
                val sx: Float
                val sy: Float
                if (spin) {
                    val ang = -strength * 0.3f * t
                    val cosA = kotlin.math.cos(ang.toDouble()).toFloat()
                    val sinA = kotlin.math.sin(ang.toDouble()).toFloat()
                    sx = cx + (odx * cosA - ody * sinA)
                    sy = cy + (odx * sinA + ody * cosA)
                } else {
                    val scale = 1f - strength * t
                    sx = cx + odx * scale
                    sy = cy + ody * scale
                }
                val ix = sx.toInt().coerceIn(0, w - 1)
                val iy = sy.toInt().coerceIn(0, h - 1)
                val p = src[iy * w + ix]
                aa += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                gg += (p ushr 8) and 0xFF
                bb += p and 0xFF
            }
            val n = samples.toFloat()
            out[y * w + x] = ((aa / n).toInt() shl 24) or ((rr / n).toInt() shl 16) or
                ((gg / n).toInt() shl 8) or (bb / n).toInt()
        }
    }
    return out
}
