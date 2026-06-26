package com.vayunmathur.files.util
import android.content.Context
import androidx.work.WorkerParameters
import okio.Buffer
import okio.FileSystem
import okio.ForwardingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.sink
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.vayunmathur.files.R

class ZipWorker(context: Context, params: WorkerParameters) : ProgressNotificationWorker(
    context,
    params,
    channelId = "zip_progress_channel",
    notificationId = 1,
    channelNameRes = R.string.zip_channel_name,
    contentTitleRes = R.string.archiving,
) {

    override suspend fun doWork(): Result {
        val sourcePaths = inputData.getStringArray("source_paths") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()
        val destPath = destPathString.toPath()

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        return try {
            val fileSystem = FileSystem.SYSTEM

            var totalSize = 0L
            sourcePaths.forEach { totalSize += calculateTotalSize(fileSystem, it.toPath()) }

            var bytesZipped = 0L

            fileSystem.write(destPath) {
                ZipOutputStream(outputStream()).use { zipOutputStream ->
                    sourcePaths.forEach { pathString ->
                        addToZip(fileSystem, pathString.toPath(), "", zipOutputStream) { bytes ->
                            bytesZipped += bytes
                            updateProgress(bytesZipped, totalSize)
                        }
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

    private fun calculateTotalSize(fileSystem: FileSystem, path: Path): Long {
        val metadata = fileSystem.metadataOrNull(path) ?: return 0L
        return if (metadata.isDirectory) fileSystem.list(path).sumOf { calculateTotalSize(fileSystem, it) }
        else metadata.size ?: 0L
    }

    private fun addToZip(
        fileSystem: FileSystem,
        path: Path,
        base: String,
        zipOutputStream: ZipOutputStream,
        onProgress: (Long) -> Unit
    ) {
        val entryName = if (base.isEmpty()) path.name else "$base/${path.name}"
        val metadata = fileSystem.metadataOrNull(path) ?: return

        if (metadata.isDirectory) {
            val children = fileSystem.list(path)
            if (children.isEmpty()) {
                zipOutputStream.putNextEntry(ZipEntry("$entryName/"))
                zipOutputStream.closeEntry()
            } else {
                children.forEach { child ->
                    addToZip(fileSystem, child, entryName, zipOutputStream, onProgress)
                }
            }
        } else {
            zipOutputStream.putNextEntry(ZipEntry(entryName))
            val countingSink = object : ForwardingSink(zipOutputStream.sink()) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    onProgress(byteCount)
                }
            }.buffer()
            fileSystem.read(path) { readAll(countingSink) }
            countingSink.flush()
            zipOutputStream.closeEntry()
        }
    }
}
