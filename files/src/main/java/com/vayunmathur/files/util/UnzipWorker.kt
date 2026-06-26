package com.vayunmathur.files.util
import android.content.Context
import androidx.work.WorkerParameters
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source
import java.util.zip.ZipInputStream
import com.vayunmathur.files.R

class UnzipWorker(context: Context, params: WorkerParameters) : ProgressNotificationWorker(
    context,
    params,
    channelId = "unzip_progress_channel",
    notificationId = 2,
    channelNameRes = R.string.unzip_channel_name,
    contentTitleRes = R.string.unzipping,
) {

    override suspend fun doWork(): Result {
        val zipPathString = inputData.getString("zip_path") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()

        val zipPath = zipPathString.toPath()
        val destPath = destPathString.toPath()

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        return try {
            val fileSystem = FileSystem.SYSTEM
            val zipFileSize = fileSystem.metadataOrNull(zipPath)?.size ?: 0L
            var totalBytesRead = 0L

            fileSystem.read(zipPath) {
                val countingInputStream = object : java.io.FilterInputStream(inputStream()) {
                    override fun read(): Int {
                        val b = super.read()
                        if (b != -1) {
                            totalBytesRead++
                            updateProgress(totalBytesRead, zipFileSize)
                        }
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val count = super.read(b, off, len)
                        if (count != -1) {
                            totalBytesRead += count
                            updateProgress(totalBytesRead, zipFileSize)
                        }
                        return count
                    }
                }

                ZipInputStream(countingInputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val entryFile = java.io.File(destPath.toString(), entry.name).canonicalFile
                        val destFile = java.io.File(destPath.toString()).canonicalFile
                        if (!entryFile.path.startsWith(destFile.path)) {
                            entry = zipInputStream.nextEntry
                            continue
                        }
                        val entryPath = entryFile.path.toPath()
                        if (entry.isDirectory) {
                            fileSystem.createDirectories(entryPath)
                        } else {
                            fileSystem.createDirectories(entryPath.parent!!)
                            fileSystem.write(entryPath) {
                                writeAll(zipInputStream.source())
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        } finally {
            cancelNotification()
        }
    }
}
