package com.vayunmathur.camera.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PanoramaEngine(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping = _isSweeping.asStateFlow()

    private val _isStitching = MutableStateFlow(false)
    val isStitching = _isStitching.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount = _frameCount.asStateFlow()

    private val _sweepAngle = MutableStateFlow(0f)
    val sweepAngle = _sweepAngle.asStateFlow()

    private val frames = mutableListOf<Bitmap>()
    private var accumulatedAngle = 0f
    private var lastTimestamp = 0L
    private var lastCaptureAngle = 0f

    @Volatile
    var latestFrame: Bitmap? = null

    companion object {
        private const val CAPTURE_INTERVAL_DEGREES = 25f
        private const val MAX_FRAMES = 8
        private const val MAX_SWEEP_DEGREES = 180f
        private const val FRAME_SCALE = 0.5f

        init {
            OpenCVLoader.initLocal()
        }
    }

    fun startSweep() {
        frames.clear()
        accumulatedAngle = 0f
        lastCaptureAngle = 0f
        lastTimestamp = 0L
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _isSweeping.value = true
        captureFrame()
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopSweep() {
        _isSweeping.value = false
        sensorManager.unregisterListener(this)
    }

    private fun captureFrame() {
        val frame = latestFrame ?: return
        val w = (frame.width * FRAME_SCALE).toInt()
        val h = (frame.height * FRAME_SCALE).toInt()
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        frames.add(scaled)
        _frameCount.value = frames.size
        lastCaptureAngle = accumulatedAngle
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_isSweeping.value) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val timestamp = event.timestamp
        if (lastTimestamp != 0L) {
            val dt = (timestamp - lastTimestamp) / 1_000_000_000f
            val yawRate = Math.toDegrees(event.values[1].toDouble()).toFloat()
            accumulatedAngle += Math.abs(yawRate * dt).toFloat()
            _sweepAngle.value = accumulatedAngle
        }
        lastTimestamp = timestamp

        if (accumulatedAngle - lastCaptureAngle >= CAPTURE_INTERVAL_DEGREES
            && frames.size < MAX_FRAMES
        ) {
            captureFrame()
        }

        if (accumulatedAngle >= MAX_SWEEP_DEGREES || frames.size >= MAX_FRAMES) {
            stopSweep()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    suspend fun stitch(): Bitmap? {
        if (frames.size < 2) return null
        _isStitching.value = true
        return withContext(Dispatchers.IO) {
            try {
                val mats = frames.map { bmp ->
                    val mat = Mat()
                    Utils.bitmapToMat(bmp, mat)
                    mat
                }

                var result = mats[0].clone()
                val orb = ORB.create(1000)
                val matcher = BFMatcher.create(Core.NORM_HAMMING, true)

                for (i in 1 until mats.size) {
                    result = stitchPair(result, mats[i], orb, matcher) ?: run {
                        mats.forEach { it.release() }
                        return@withContext null
                    }
                }

                mats.forEach { it.release() }

                val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(result, bitmap)
                result.release()
                bitmap
            } catch (_: Exception) {
                null
            } finally {
                _isStitching.value = false
            }
        }
    }

    private fun stitchPair(base: Mat, next: Mat, orb: ORB, matcher: BFMatcher): Mat? {
        val kp1 = MatOfKeyPoint()
        val desc1 = Mat()
        val kp2 = MatOfKeyPoint()
        val desc2 = Mat()

        val gray1 = Mat()
        val gray2 = Mat()
        Imgproc.cvtColor(base, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(next, gray2, Imgproc.COLOR_RGBA2GRAY)

        orb.detectAndCompute(gray1, Mat(), kp1, desc1)
        orb.detectAndCompute(gray2, Mat(), kp2, desc2)
        gray1.release()
        gray2.release()

        if (desc1.empty() || desc2.empty() || desc1.rows() < 10 || desc2.rows() < 10) {
            return null
        }

        val matches = MatOfDMatch()
        matcher.match(desc2, desc1, matches)
        desc1.release()
        desc2.release()

        val matchList = matches.toList().sortedBy { it.distance }
        val goodMatches = matchList.take((matchList.size * 0.3).toInt().coerceAtLeast(10))
        if (goodMatches.size < 10) return null

        val kpList1 = kp1.toList()
        val kpList2 = kp2.toList()

        val srcPts = MatOfPoint2f(*goodMatches.map { kpList2[it.queryIdx].pt }.toTypedArray())
        val dstPts = MatOfPoint2f(*goodMatches.map { kpList1[it.trainIdx].pt }.toTypedArray())

        val mask = MatOfByte()
        val H = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 5.0, mask)
        srcPts.release()
        dstPts.release()
        kp1.release()
        kp2.release()

        if (H.empty()) return null

        val corners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(next.cols().toDouble(), 0.0),
            Point(next.cols().toDouble(), next.rows().toDouble()),
            Point(0.0, next.rows().toDouble())
        )
        val warpedCorners = MatOfPoint2f()
        Core.perspectiveTransform(corners, warpedCorners, H)
        corners.release()

        val allPts = warpedCorners.toList() + listOf(
            Point(0.0, 0.0),
            Point(base.cols().toDouble(), 0.0),
            Point(base.cols().toDouble(), base.rows().toDouble()),
            Point(0.0, base.rows().toDouble())
        )
        warpedCorners.release()

        val minX = allPts.minOf { it.x }
        val minY = allPts.minOf { it.y }
        val maxX = allPts.maxOf { it.x }
        val maxY = allPts.maxOf { it.y }

        val translation = Mat.eye(3, 3, CvType.CV_64F)
        translation.put(0, 2, -minX)
        translation.put(1, 2, -minY)

        val outW = (maxX - minX).toInt()
        val outH = (maxY - minY).toInt()
        if (outW <= 0 || outH <= 0 || outW > 10000 || outH > 10000) {
            translation.release()
            H.release()
            return null
        }

        val warpedNext = Mat()
        val combinedH = Mat()
        Core.gemm(translation, H, 1.0, Mat(), 0.0, combinedH)
        Imgproc.warpPerspective(next, warpedNext, combinedH, Size(outW.toDouble(), outH.toDouble()))

        val output = warpedNext.clone()
        val roi = Rect((-minX).toInt(), (-minY).toInt(), base.cols(), base.rows())
        if (roi.x >= 0 && roi.y >= 0 && roi.x + roi.width <= output.cols() && roi.y + roi.height <= output.rows()) {
            base.copyTo(Mat(output, roi))
        }

        translation.release()
        H.release()
        combinedH.release()
        warpedNext.release()

        return output
    }

    fun saveToMediaStore(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PANO_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ) ?: return
        context.contentResolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        }
    }

    fun reset() {
        frames.forEach { it.recycle() }
        frames.clear()
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _isSweeping.value = false
        _isStitching.value = false
    }
}
