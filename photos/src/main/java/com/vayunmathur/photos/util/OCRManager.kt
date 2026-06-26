package com.vayunmathur.photos.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.vayunmathur.library.util.SecureResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.coroutines.resume

class OCRManager(private val context: Context) {

    companion object {
        private const val TAG = "OCRManager"
        private const val OA_PACKAGE = "com.vayunmathur.openassistant"
        private const val OA_SERVICE = "$OA_PACKAGE.util.InferenceService"
        private const val MAX_IMAGE_DIMENSION = 512
        private const val SCHEMA = """{"type":"object","properties":{"ocr_text":{"type":"string","description":"All visible text extracted from the image"},"description":{"type":"string","description":"A brief description of the image contents"}},"required":["ocr_text","description"]}"""
    }

    suspend fun runOCR(uri: Uri): Pair<String, String>? {
        Log.d(TAG, "Starting OCR via OpenAssistant")

        if (!isAvailable()) {
            Log.e(TAG, "OpenAssistant is not installed")
            return null
        }

        return withTimeoutOrNull(60000) {
            try {
                val resizedFile = withContext(Dispatchers.IO) { resizeImage(uri) } ?: return@withTimeoutOrNull null
                try {
                    dispatchInference(resizedFile)
                } finally {
                    resizedFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error running OCR", e)
                null
            }
        }
    }

    private fun resizeImage(uri: Uri): File? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val maxDim = maxOf(w, h)
                if (maxDim > MAX_IMAGE_DIMENSION) {
                    val scale = MAX_IMAGE_DIMENSION.toFloat() / maxDim
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }

            val file = File(context.cacheDir, "ocr_tmp_${System.nanoTime()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize image", e)
            null
        }
    }

    private suspend fun dispatchInference(file: File): Pair<String, String>? =
        suspendCancellableCoroutine { cont ->
            val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data ->
                if (code == 0) {
                    val json = data?.getString("json_result")
                    if (json != null) {
                        try {
                            val obj = Json.parseToJsonElement(json).jsonObject
                            val ocrText = obj["ocr_text"]?.jsonPrimitive?.content ?: ""
                            val description = obj["description"]?.jsonPrimitive?.content ?: ""
                            cont.resume(Pair(ocrText, description))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse JSON result: $json", e)
                            cont.resume(null)
                        }
                    } else {
                        cont.resume(null)
                    }
                } else {
                    val error = data?.getString("error") ?: "Unknown error"
                    Log.e(TAG, "Inference failed: $error")
                    cont.resume(null)
                }
            }

            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val intent = Intent().apply {
                component = ComponentName(OA_PACKAGE, OA_SERVICE)
                putExtra("user_text", "Extract all visible text from this image and describe what the image shows.")
                putParcelableArrayListExtra("image_uris", arrayListOf(fileUri))
                putExtra("schema", SCHEMA)
                putExtra("RECEIVER", receiver as android.os.ResultReceiver)
            }

            try {
                context.grantUriPermission(OA_PACKAGE, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start InferenceService", e)
                cont.resume(null)
            }
        }

    fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(OA_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
