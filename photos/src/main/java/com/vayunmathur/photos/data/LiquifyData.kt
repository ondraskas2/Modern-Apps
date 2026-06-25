package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class LiquifyTool { Push, Twirl, Pucker, Bloat, Reconstruct }

data class LiquifyOp(
    val tool: LiquifyTool,
    val x: Float,            // normalized 0..1 center
    val y: Float,
    val dx: Float = 0f,      // normalized drag delta (for Push)
    val dy: Float = 0f,
    val radius: Float = 0.15f, // normalized
    val strength: Float = 0.5f,
)

data class LiquifyParams(val ops: List<LiquifyOp> = emptyList()) {
    fun isIdentity(): Boolean = ops.isEmpty()
}

fun LiquifyParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)

    val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (isIdentity() || w == 0 || h == 0) {
        output.setPixels(src, 0, w, 0, 0, w, h)
        return output
    }

    val dispX = FloatArray(w * h)
    val dispY = FloatArray(w * h)

    val maxDim = max(w, h).toFloat()

    for (op in ops) {
        val cx = op.x * w
        val cy = op.y * h
        val radiusPx = op.radius * maxDim
        if (radiusPx <= 0f) continue

        val angleBase = (op.strength * Math.PI).toFloat()
        val dragX = op.dx * maxDim
        val dragY = op.dy * maxDim

        val minX = max(0, (cx - radiusPx).toInt())
        val maxX = minOf(w - 1, (cx + radiusPx).toInt())
        val minY = max(0, (cy - radiusPx).toInt())
        val maxY = minOf(h - 1, (cy + radiusPx).toInt())

        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val ox = px - cx
                val oy = py - cy
                val dist = sqrt(ox * ox + oy * oy)
                if (dist > radiusPx) continue

                val raw = (1f - dist / radiusPx).coerceIn(0f, 1f)
                val t = raw * raw * (3f - 2f * raw) // smoothstep falloff

                val idx = py * w + px

                when (op.tool) {
                    LiquifyTool.Push -> {
                        dispX[idx] += -dragX * op.strength * t
                        dispY[idx] += -dragY * op.strength * t
                    }
                    LiquifyTool.Twirl -> {
                        val angle = angleBase * t
                        val c = cos(angle)
                        val s = sin(angle)
                        val rotX = ox * c - oy * s
                        val rotY = ox * s + oy * c
                        dispX[idx] += rotX - ox
                        dispY[idx] += rotY - oy
                    }
                    LiquifyTool.Pucker -> {
                        dispX[idx] += (cx - px) * op.strength * t * 0.5f
                        dispY[idx] += (cy - py) * op.strength * t * 0.5f
                    }
                    LiquifyTool.Bloat -> {
                        dispX[idx] += (px - cx) * op.strength * t * 0.5f
                        dispY[idx] += (py - cy) * op.strength * t * 0.5f
                    }
                    LiquifyTool.Reconstruct -> {
                        val factor = (1f - op.strength * t).coerceIn(0f, 1f)
                        dispX[idx] *= factor
                        dispY[idx] *= factor
                    }
                }
            }
        }
    }

    val dst = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val idx = y * w + x
            val sx = x + dispX[idx]
            val sy = y + dispY[idx]
            dst[idx] = sampleBilinear(src, w, h, sx, sy)
        }
    }

    output.setPixels(dst, 0, w, 0, 0, w, h)
    return output
}

private fun sampleBilinear(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
    val cx = fx.coerceIn(0f, (w - 1).toFloat())
    val cy = fy.coerceIn(0f, (h - 1).toFloat())

    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val x1 = minOf(x0 + 1, w - 1)
    val y1 = minOf(y0 + 1, h - 1)

    val tx = cx - x0
    val ty = cy - y0

    val p00 = src[y0 * w + x0]
    val p10 = src[y0 * w + x1]
    val p01 = src[y1 * w + x0]
    val p11 = src[y1 * w + x1]

    val a = lerpChannel(p00, p10, p01, p11, tx, ty, 24)
    val r = lerpChannel(p00, p10, p01, p11, tx, ty, 16)
    val g = lerpChannel(p00, p10, p01, p11, tx, ty, 8)
    val b = lerpChannel(p00, p10, p01, p11, tx, ty, 0)

    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun lerpChannel(
    p00: Int,
    p10: Int,
    p01: Int,
    p11: Int,
    tx: Float,
    ty: Float,
    shift: Int,
): Int {
    val c00 = ((p00 shr shift) and 0xFF).toFloat()
    val c10 = ((p10 shr shift) and 0xFF).toFloat()
    val c01 = ((p01 shr shift) and 0xFF).toFloat()
    val c11 = ((p11 shr shift) and 0xFF).toFloat()

    val top = c00 + (c10 - c00) * tx
    val bottom = c01 + (c11 - c01) * tx
    val value = top + (bottom - top) * ty

    return value.toInt().coerceIn(0, 255)
}
