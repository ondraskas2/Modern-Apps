package com.vayunmathur.camera.util

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Centralizes the DCIM/Camera MediaStore writes shared by photo, video and panorama capture. */
object MediaStoreSaver {

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun imageValues(displayName: String): ContentValues = contentValues(displayName, "image/jpeg")

    fun videoValues(displayName: String): ContentValues = contentValues(displayName, "video/mp4")

    private fun contentValues(displayName: String, mimeType: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
    }

    fun saveBitmap(
        resolver: ContentResolver,
        values: ContentValues,
        bitmap: Bitmap,
        quality: Int = 95,
    ): Uri? {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
        }
        return uri
    }

    fun saveVideoFile(resolver: ContentResolver, values: ContentValues, file: File): Uri? {
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os ->
            file.inputStream().use { input -> input.copyTo(os) }
        }
        return uri
    }
}
