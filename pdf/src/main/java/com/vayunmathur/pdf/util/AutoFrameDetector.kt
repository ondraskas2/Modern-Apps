package com.vayunmathur.pdf.util

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.vayunmathur.pdf.model.Quadrilateral
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AutoFrameDetector {

    private const val MAX_DIM = 500
    private const val MIN_AREA_FRACTION = 0.05f
    private const val MIN_COMPONENT_FRACTION = 0.005f

    fun detect(bitmap: Bitmap): Quadrilateral? {
        val maxDim = max(bitmap.width, bitmap.height)
        val scale = if (maxDim > MAX_DIM) MAX_DIM.toFloat() / maxDim else 1f
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(10)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(10)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap

        return try {
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = grayscale(pixels)
            val blurred = gaussianBlur(gray, w, h)
            val edges = sobelMagnitude(blurred, w, h)
            val thresh = otsuThreshold(edges)
            val binary = BooleanArray(w * h) { edges[it] > thresh }
            val dilated = dilate(binary, w, h)
            findQuadFromComponents(dilated, w, h)
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun grayscale(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }

    private fun gaussianBlur(src: IntArray, w: Int, h: Int): IntArray {
        val k = intArrayOf(2,4,5,4,2, 4,9,12,9,4, 5,12,15,12,5, 4,9,12,9,4, 2,4,5,4,2)
        val kSum = 159
        val dst = IntArray(w * h)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                var sum = 0; var ki = 0
                for (ky in -2..2) for (kx in -2..2) {
                    sum += src[(y + ky) * w + (x + kx)] * k[ki++]
                }
                dst[y * w + x] = sum / kSum
            }
        }
        return dst
    }

    private fun sobelMagnitude(gray: IntArray, w: Int, h: Int): IntArray {
        val mag = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = -gray[(y-1)*w+(x-1)] - 2*gray[y*w+(x-1)] - gray[(y+1)*w+(x-1)] +
                    gray[(y-1)*w+(x+1)] + 2*gray[y*w+(x+1)] + gray[(y+1)*w+(x+1)]
                val gy = -gray[(y-1)*w+(x-1)] - 2*gray[(y-1)*w+x] - gray[(y-1)*w+(x+1)] +
                    gray[(y+1)*w+(x-1)] + 2*gray[(y+1)*w+x] + gray[(y+1)*w+(x+1)]
                mag[y*w+x] = min(255, sqrt((gx.toLong()*gx + gy.toLong()*gy).toFloat()).roundToInt())
            }
        }
        return mag
    }

    private fun otsuThreshold(data: IntArray): Int {
        val hist = IntArray(256)
        for (v in data) hist[v.coerceIn(0, 255)]++
        val total = data.size
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var sumB = 0L; var wB = 0; var best = 0; var maxVar = 0.0
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val diff = sumB.toDouble() / wB - (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * diff * diff
            if (v > maxVar) { maxVar = v; best = t }
        }
        return best
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val dst = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (src[y*w+x] || src[(y-1)*w+x] || src[(y+1)*w+x] ||
                src[y*w+(x-1)] || src[y*w+(x+1)]) dst[y*w+x] = true
        }
        return dst
    }

    private fun findQuadFromComponents(binary: BooleanArray, w: Int, h: Int): Quadrilateral? {
        val labels = IntArray(w * h) { -1 }
        val parent = mutableListOf<Int>()

        fun find(x: Int): Int {
            var r = x; while (parent[r] != r) r = parent[r]
            var c = x; while (c != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        for (y in 0 until h) for (x in 0 until w) {
            if (!binary[y * w + x]) continue
            val neighbors = mutableListOf<Int>()
            if (y > 0 && binary[(y-1)*w+x]) neighbors.add(labels[(y-1)*w+x])
            if (x > 0 && binary[y*w+(x-1)]) neighbors.add(labels[y*w+(x-1)])
            if (y > 0 && x > 0 && binary[(y-1)*w+(x-1)]) neighbors.add(labels[(y-1)*w+(x-1)])
            if (y > 0 && x < w-1 && binary[(y-1)*w+(x+1)]) neighbors.add(labels[(y-1)*w+(x+1)])

            if (neighbors.isEmpty()) {
                labels[y*w+x] = parent.size; parent.add(parent.size)
            } else {
                val minL = neighbors.min()
                labels[y*w+x] = minL
                for (n in neighbors) union(n, minL)
            }
        }

        val sizes = mutableMapOf<Int, Int>()
        for (i in labels.indices) {
            if (labels[i] >= 0) { labels[i] = find(labels[i]); sizes[labels[i]] = (sizes[labels[i]] ?: 0) + 1 }
        }

        val minSize = (w * h * MIN_COMPONENT_FRACTION).toInt()
        val candidates = sizes.filter { it.value >= minSize }.entries.sortedByDescending { it.value }

        for ((label, _) in candidates) {
            val points = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until h) for (x in 0 until w) {
                if (labels[y*w+x] == label) points.add(x to y)
            }
            val hull = convexHull(points)
            if (hull.size < 4) continue
            val corners = best4Corners(hull) ?: continue
            val area = polyArea(corners)
            if (area < w * h * MIN_AREA_FRACTION) continue
            val sorted = sortCorners(corners)
            return Quadrilateral(
                topLeft = Offset(sorted[0].first.toFloat() / w, sorted[0].second.toFloat() / h),
                topRight = Offset(sorted[1].first.toFloat() / w, sorted[1].second.toFloat() / h),
                bottomRight = Offset(sorted[2].first.toFloat() / w, sorted[2].second.toFloat() / h),
                bottomLeft = Offset(sorted[3].first.toFloat() / w, sorted[3].second.toFloat() / h)
            )
        }
        return null
    }

    private fun convexHull(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val sorted = points.sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return sorted
        val lower = mutableListOf<Pair<Int, Int>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size-2], lower.last(), p) <= 0) lower.removeAt(lower.size-1)
            lower.add(p)
        }
        val upper = mutableListOf<Pair<Int, Int>>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size-2], upper.last(), p) <= 0) upper.removeAt(upper.size-1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Long =
        (a.first - o.first).toLong() * (b.second - o.second) -
            (a.second - o.second).toLong() * (b.first - o.first)

    private fun best4Corners(hull: List<Pair<Int, Int>>): List<Pair<Int, Int>>? {
        val n = hull.size
        if (n < 4) return null
        if (n == 4) return hull
        val angles = (0 until n).map { i ->
            val prev = hull[(i - 1 + n) % n]; val curr = hull[i]; val next = hull[(i + 1) % n]
            val v1x = (prev.first - curr.first).toDouble(); val v1y = (prev.second - curr.second).toDouble()
            val v2x = (next.first - curr.first).toDouble(); val v2y = (next.second - curr.second).toDouble()
            val len1 = sqrt(v1x*v1x + v1y*v1y); val len2 = sqrt(v2x*v2x + v2y*v2y)
            if (len1 < 1e-6 || len2 < 1e-6) Math.PI
            else acos(((v1x*v2x + v1y*v2y) / (len1 * len2)).coerceIn(-1.0, 1.0))
        }
        val indexed = angles.mapIndexed { idx, angle -> idx to angle }.sortedBy { it.second }
        val minSep = max(1, n / 6)
        val sel = mutableListOf<Int>()
        for ((idx, _) in indexed) {
            if (sel.all { e -> val d = abs(idx - e); min(d, n - d) >= minSep }) {
                sel.add(idx); if (sel.size == 4) break
            }
        }
        if (sel.size < 4) { sel.clear(); val step = n / 4; for (i in 0 until 4) sel.add(i * step) }
        return sel.sorted().map { hull[it] }
    }

    private fun polyArea(pts: List<Pair<Int, Int>>): Float {
        var area = 0f; val n = pts.size
        for (i in 0 until n) { val j = (i+1) % n; area += pts[i].first.toFloat()*pts[j].second - pts[j].first.toFloat()*pts[i].second }
        return abs(area) / 2f
    }

    private fun sortCorners(corners: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val bySum = corners.sortedBy { it.first + it.second }
        val tl = bySum.first(); val br = bySum.last()
        val remaining = corners.toMutableList(); remaining.remove(tl); remaining.remove(br)
        val byDiff = remaining.sortedBy { it.first - it.second }
        return listOf(tl, byDiff.last(), br, byDiff.first())
    }
}
