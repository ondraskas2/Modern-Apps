package com.vayunmathur.photos.data

import kotlin.math.roundToInt

/**
 * A normalized selection mask (values 0..1) at [width] x [height]. A regular class
 * (not data) so that equality compares the [mask] contents via contentEquals.
 */
class Selection(
    val mask: FloatArray,
    val width: Int,
    val height: Int,
    val featherRadius: Float = 0f,
) {
    fun isEmpty(): Boolean = mask.all { it <= 0f }

    fun invert(): Selection =
        Selection(FloatArray(mask.size) { 1f - mask[it] }, width, height, featherRadius)

    fun applyFeather(radius: Float): Selection {
        val r = radius.toInt()
        if (r <= 0) return Selection(mask.copyOf(), width, height, radius)
        val horizontal = FloatArray(mask.size)
        val windowSize = (2 * r + 1).toFloat()
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -r..r) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    sum += mask[rowOffset + sx]
                }
                horizontal[rowOffset + x] = sum / windowSize
            }
        }
        val blurred = FloatArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -r..r) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    sum += horizontal[sy * width + x]
                }
                blurred[y * width + x] = sum / windowSize
            }
        }
        return Selection(blurred, width, height, radius)
    }

    fun toLayerMask(): LayerMask = LayerMask(mask.copyOf(), width, height)

    override fun equals(other: Any?): Boolean =
        other is Selection &&
            width == other.width &&
            height == other.height &&
            mask.contentEquals(other.mask)

    override fun hashCode(): Int {
        var hash = mask.contentHashCode()
        hash = 31 * hash + width
        hash = 31 * hash + height
        return hash
    }

    companion object {
        fun rectangle(
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): Selection {
            val mask = FloatArray(width * height)
            val l = (left * width).roundToInt()
            val t = (top * height).roundToInt()
            val rt = (right * width).roundToInt()
            val b = (bottom * height).roundToInt()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (x in l until rt && y in t until b) {
                        mask[y * width + x] = 1f
                    }
                }
            }
            return Selection(mask, width, height)
        }

        fun ellipse(
            width: Int,
            height: Int,
            cx: Float,
            cy: Float,
            rx: Float,
            ry: Float,
        ): Selection {
            val mask = FloatArray(width * height)
            val centerX = cx * width
            val centerY = cy * height
            val radX = rx * width
            val radY = ry * height
            if (radX > 0f && radY > 0f) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = (x - centerX) / radX
                        val ny = (y - centerY) / radY
                        if (nx * nx + ny * ny <= 1f) {
                            mask[y * width + x] = 1f
                        }
                    }
                }
            }
            return Selection(mask, width, height)
        }
    }
}
