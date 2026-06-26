package com.vayunmathur.photos.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.vayunmathur.photos.data.VaultPhoto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureFolderManager(val context: Context) {
    private val secureFolder = File(context.filesDir, "secure_vault")

    init {
        if (!secureFolder.exists()) secureFolder.mkdirs()
    }

    private fun getSecretKey(password: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getCipher(mode: Int, key: SecretKey, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (iv != null) {
            cipher.init(mode, key, GCMParameterSpec(128, iv))
        } else {
            cipher.init(mode, key)
        }
        return cipher
    }

    fun encryptAndMove(uri: Uri, name: String, password: String): Pair<String, String> {
        val key = getSecretKey(password)
        val timestamp = System.currentTimeMillis()
        val fileName = "${timestamp}_${name}.enc"
        val thumbName = "${timestamp}_${name}_thumb.enc"
        
        val outputFile = File(secureFolder, fileName)
        val thumbFile = File(secureFolder, thumbName)
        
        // 1. Generate Thumbnail
        val bitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)

        // 2. Encrypt Thumbnail
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        bitmap.recycle()
        val thumbBytes = baos.toByteArray()
        
        FileOutputStream(thumbFile).use { fos ->
            val cipher = getCipher(Cipher.ENCRYPT_MODE, key)
            fos.write(cipher.iv)
            CipherOutputStream(fos, cipher).use { output ->
                output.write(thumbBytes)
            }
        }

        // 3. Encrypt Main File
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outputFile).use { fos ->
                val cipher = getCipher(Cipher.ENCRYPT_MODE, key)
                fos.write(cipher.iv)
                CipherOutputStream(fos, cipher).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        return outputFile.absolutePath to thumbFile.absolutePath
    }

    fun decryptThumbnail(path: String, password: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        
        val key = getSecretKey(password)
        return try {
            FileInputStream(file).use { fis ->
                val iv = ByteArray(12)
                if (fis.read(iv) != 12) return null
                val cipher = getCipher(Cipher.DECRYPT_MODE, key, iv)
                CipherInputStream(fis, cipher).use { input ->
                    val bytes = input.readBytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun decryptAndRestore(vaultPhoto: VaultPhoto, password: String): Uri? {
        val inputFile = File(vaultPhoto.path)
        if (!inputFile.exists()) return null

        val key = getSecretKey(password)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, vaultPhoto.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (vaultPhoto.videoDuration != null) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
        }

        val collection = if (vaultPhoto.videoDuration != null) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, contentValues) ?: return null

        context.contentResolver.openOutputStream(uri)?.use { output ->
            FileInputStream(inputFile).use { fis ->
                val iv = ByteArray(12)
                if (fis.read(iv) != 12) {
                    // Don't leave an empty MediaStore row behind on a malformed file.
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
                val cipher = getCipher(Cipher.DECRYPT_MODE, key, iv)
                CipherInputStream(fis, cipher).use { input ->
                    input.copyTo(output)
                }
            }
        }

        // Delete encrypted files
        inputFile.delete()
        File(vaultPhoto.thumbnailPath).delete()

        return uri
    }

}
