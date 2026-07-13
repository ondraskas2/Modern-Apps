package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.FlannBasedMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Real multi-frame computational night capture. Takes a burst of already upright/consistent
 * (uncompressed) frames, aligns them to a reference (handheld-capable), merges them to cut noise
 * (~√N read/shot-noise reduction), then brightens the result so it reads as a bright night shot.
 *
 * Source-agnostic: it only sees a [List]<[Bitmap]>, so the burst can come from the ImageAnalysis
 * stream today or a dedicated Camera2 full-res session later. Alignment reuses the same OpenCV
 * SIFT + FLANN + median-translation approach as [PanoramaEngine.findTranslation].
 */
object NightCaptureEngine {
    // --- Tunables ---

    /** How many frames to stack. Higher = less noise but longer capture + more work. */
    const val NIGHT_BURST_COUNT = 6

    // Feature detection runs on a downscaled gray copy (like the panorama path) with the resulting
    // offsets scaled back up, keeping SIFT responsive over high-res frames.
    private const val ALIGN_DOWNSCALE = 0.5

    private const val SIFT_FEATURE_CAP = 2000
    private const val LOWE_RATIO = 0.7f
    private const val MIN_GOOD_MATCHES = 8

    // Brightening baked into the merged result.
    private const val NIGHT_GAIN = 1.6f
    private const val NIGHT_SHADOW_LIFT = 18f

    private const val TAG = "NightCaptureEngine"

    init {
        // Idempotent; PanoramaEngine also initializes OpenCV, but keep this self-contained so the
        // engine works regardless of which OpenCV consumer ran first.
        OpenCVLoader.initLocal()
    }

    /**
     * Aligns and merges [burst] into a single brightened bitmap. Returns null only when [burst] is
     * empty. Frames that fail alignment are skipped (the shot still succeeds). Runs off the main
     * thread; the caller owns recycling of the input bitmaps.
     */
    suspend fun merge(burst: List<Bitmap>): Bitmap? = withContext(Dispatchers.Default) {
        if (burst.isEmpty()) return@withContext null

        // The middle frame is least likely to be a start/stop-jerk frame.
        val refIndex = burst.size / 2
        val reference = burst[refIndex]
        val w = reference.width
        val h = reference.height
        val n = w * h

        val refPixels = IntArray(n)
        reference.getPixels(refPixels, 0, w, 0, 0, w, h)

        val accumR = FloatArray(n)
        val accumG = FloatArray(n)
        val accumB = FloatArray(n)
        val coverage = IntArray(n)

        // Reference contributes at (0,0) so every pixel has at least one sample.
        for (i in 0 until n) {
            val p = refPixels[i]
            accumR[i] = ((p shr 16) and 0xFF).toFloat()
            accumG[i] = ((p shr 8) and 0xFF).toFloat()
            accumB[i] = (p and 0xFF).toFloat()
            coverage[i] = 1
        }

        if (burst.size > 1) {
            val sift = SIFT.create(SIFT_FEATURE_CAP)
            val matcher = FlannBasedMatcher.create()

            val refGray = toDownscaledGray(reference)
            val refKp = MatOfKeyPoint()
            val refDesc = Mat()
            sift.detectAndCompute(refGray, Mat(), refKp, refDesc)
            refGray.release()
            val refKpList = refKp.toList()
            refKp.release()

            if (!refDesc.empty() && refDesc.rows() >= 10) {
                val framePixels = IntArray(n)
                for (idx in burst.indices) {
                    if (idx == refIndex) continue
                    val frame = burst[idx]
                    if (frame.width != w || frame.height != h) continue

                    val offset = computeOffset(frame, refKpList, refDesc, sift, matcher)
                    if (offset == null) {
                        Log.d(TAG, "Frame $idx skipped (too few matches)")
                        continue
                    }
                    val (dx, dy) = offset

                    frame.getPixels(framePixels, 0, w, 0, 0, w, h)
                    for (y in 0 until h) {
                        val ty = y + dy
                        if (ty < 0 || ty >= h) continue
                        val dstRow = ty * w
                        val srcRow = y * w
                        for (x in 0 until w) {
                            val tx = x + dx
                            if (tx < 0 || tx >= w) continue
                            val p = framePixels[srcRow + x]
                            val ti = dstRow + tx
                            accumR[ti] += ((p shr 16) and 0xFF).toFloat()
                            accumG[ti] += ((p shr 8) and 0xFF).toFloat()
                            accumB[ti] += (p and 0xFF).toFloat()
                            coverage[ti] += 1
                        }
                    }
                }
            } else {
                Log.d(TAG, "Reference produced no usable descriptors; merging reference only")
            }
            refDesc.release()
        }

        val out = IntArray(n)
        for (i in 0 until n) {
            val c = coverage[i]
            val r: Int
            val g: Int
            val b: Int
            if (c > 0) {
                r = brighten(accumR[i] / c)
                g = brighten(accumG[i] / c)
                b = brighten(accumB[i] / c)
            } else {
                // Unreachable while the reference is always accumulated, but fall back to the
                // reference pixel defensively.
                val p = refPixels[i]
                r = brighten(((p shr 16) and 0xFF).toFloat())
                g = brighten(((p shr 8) and 0xFF).toFloat())
                b = brighten((p and 0xFF).toFloat())
            }
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    /** Applies the night gain + shadow lift to a single 0..255 channel value. */
    private fun brighten(value: Float): Int =
        (value * NIGHT_GAIN + NIGHT_SHADOW_LIFT).roundToInt().coerceIn(0, 255)

    /**
     * Integer (dx, dy) translation that maps [frame] pixels onto the reference coordinate space,
     * i.e. a frame pixel at (x, y) belongs at (x + dx, y + dy). Returns null if too few good
     * matches. Mirrors [PanoramaEngine.findTranslation]'s knn(2) + Lowe ratio + median approach,
     * but computes on downscaled gray and scales the offset back to full resolution.
     */
    private fun computeOffset(
        frame: Bitmap,
        refKpList: List<org.opencv.core.KeyPoint>,
        refDesc: Mat,
        sift: SIFT,
        matcher: FlannBasedMatcher,
    ): Pair<Int, Int>? {
        val gray = toDownscaledGray(frame)
        val kp = MatOfKeyPoint()
        val desc = Mat()
        sift.detectAndCompute(gray, Mat(), kp, desc)
        gray.release()

        if (desc.empty() || desc.rows() < 10) {
            kp.release()
            desc.release()
            return null
        }

        val knnMatches = mutableListOf<MatOfDMatch>()
        // query = frame descriptors, train = reference descriptors (same order as PanoramaEngine).
        matcher.knnMatch(desc, refDesc, knnMatches, 2)
        desc.release()

        val goodMatches = knnMatches.mapNotNull { m ->
            val list = m.toList()
            if (list.size >= 2 && list[0].distance < LOWE_RATIO * list[1].distance) list[0] else null
        }
        val kpList = kp.toList()
        kp.release()

        if (goodMatches.size < MIN_GOOD_MATCHES) return null

        val dxList = goodMatches.map { m ->
            refKpList[m.trainIdx].pt.x - kpList[m.queryIdx].pt.x
        }.sorted()
        val dyList = goodMatches.map { m ->
            refKpList[m.trainIdx].pt.y - kpList[m.queryIdx].pt.y
        }.sorted()

        val medianDx = dxList[dxList.size / 2]
        val medianDy = dyList[dyList.size / 2]
        // Offsets were measured on the downscaled image; scale them back to full resolution.
        return Pair((medianDx / ALIGN_DOWNSCALE).roundToInt(), (medianDy / ALIGN_DOWNSCALE).roundToInt())
    }

    /** Converts [bitmap] to a downscaled single-channel gray Mat for cheap feature detection. */
    private fun toDownscaledGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val small = Mat()
        Imgproc.resize(
            gray,
            small,
            Size(gray.cols() * ALIGN_DOWNSCALE, gray.rows() * ALIGN_DOWNSCALE)
        )
        gray.release()
        return small
    }
}
