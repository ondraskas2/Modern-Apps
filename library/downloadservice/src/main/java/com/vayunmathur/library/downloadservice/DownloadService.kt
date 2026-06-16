package com.vayunmathur.library.downloadservice

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.*
import com.vayunmathur.library.network.NetworkClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "high_speed_download_channel"
        private const val MAX_RETRIES = 5
        private const val BUFFER_SIZE = 65536
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = NetworkClient

    override fun onCreate() {
        super.onCreate()
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        // Handle deprecated WIFI_MODE_FULL_HIGH_PERF
        val wifiMode =
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY

        wifiLock = wm.createWifiLock(wifiMode, "AiChat:HighPerf")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiChat:DownloadWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val urls = intent?.getStringArrayExtra("urls") ?: emptyArray()
        val fileNames = intent?.getStringArrayExtra("fileNames") ?: emptyArray()

        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Preparing high-speed transfer..."),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        wifiLock?.acquire()
        wakeLock?.acquire()

        serviceScope.launch {
            val ds = DataStoreUtils.getInstance(applicationContext)

            urls.forEachIndexed { index, url ->
                val fileName = fileNames[index]
                val destFile = File(getExternalFilesDir(null), fileName)

                if(ds.getBoolean("done_$fileName", false)) return@forEachIndexed

                var retryCount = 0
                var success = false

                while (retryCount < MAX_RETRIES && !success && isActive) {
                    try {
                        performDownload(url, destFile, ds, fileName)
                        success = true
                    } catch (e: Exception) {
                        retryCount++
                        val errorMsg = "Retry $retryCount/$MAX_RETRIES: ${e.message}"
                        updateNotification(errorMsg)
                        ds.setString("error_$fileName", errorMsg)

                        delay(TimeUnit.SECONDS.toMillis(2.0.pow(retryCount.toDouble()).toLong()))
                    }
                }
            }
            val allDone = urls.indices.all { index ->
                val fileName = fileNames[index]
                ds.getBoolean("done_$fileName", false) && verifyFile(urls[index], fileName)
            }
            if (allDone) {
                ds.setBoolean("dbSetupComplete", true)
            }
            cleanupAndStop()
        }

        return START_REDELIVER_INTENT
    }

    private suspend fun performDownload(
        url: String,
        file: File,
        ds: DataStoreUtils,
        fileName: String
    ) = withContext(Dispatchers.IO) {
        val existingSize = if (file.exists()) file.length() else 0L
        var lastBytes = existingSize
        var lastTime = System.currentTimeMillis()

        val headers = if (existingSize > 0) mapOf("Range" to "bytes=$existingSize-") else emptyMap()

        client.stream(url, headers = headers) { stream, response ->
            if (stream == null) {
                if (response.status == 416) {
                    markAsDone(ds, fileName)
                } else {
                    throw IOException("Server returned ${response.status}")
                }
                return@stream
            }
            val isResuming = existingSize > 0
            val contentLength = response.contentLength
            val totalSize = if (contentLength != null) {
                if (isResuming) existingSize + contentLength else contentLength
            } else null

            var downloaded = if (isResuming) existingSize else 0L

            FileOutputStream(file, isResuming).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive && !stream.isClosedForRead) {
                    val read = stream.read(buffer)
                    if (read == -1) break

                    output.write(buffer, 0, read)
                    downloaded += read

                    val currentTime = System.currentTimeMillis()
                    val timeDiffMs = currentTime - lastTime

                    if (timeDiffMs >= 1000L || (totalSize != null && downloaded == totalSize)) {
                        val progress = if (totalSize != null && totalSize > 0) downloaded.toDouble() / totalSize else 0.0
                        val speedMbps = ((downloaded - lastBytes) * 8.0) / 1_000_000.0 / (timeDiffMs / 1000.0)

                        ds.setDouble("progress_$fileName", progress)
                        ds.setDouble("speed_$fileName", if (totalSize != null && downloaded == totalSize) 0.0 else speedMbps)

                        val speedText = if (speedMbps > 0) "${speedMbps.toInt()} Mbps" else "Finishing..."
                        updateNotification("Downloading $fileName: ${(progress * 100).toInt()}% ($speedText)")

                        lastBytes = downloaded
                        lastTime = currentTime
                    }
                }
                output.flush()
            }

            if (totalSize != null && totalSize > 0 && downloaded >= totalSize) {
                markAsDone(ds, fileName)
            } else if (isActive && totalSize != null && totalSize > 0) {
                throw IOException("Connection lost: $downloaded/$totalSize bytes received")
            }
        }
    }

    private suspend fun markAsDone(ds: DataStoreUtils, fileName: String) {
        ds.setBoolean("done_$fileName", true)
        ds.setDouble("progress_$fileName", 1.0)
        ds.setDouble("speed_$fileName", 0.0)
    }

    private suspend fun verifyFile(url: String, fileName: String): Boolean {
        val file = File(getExternalFilesDir(null), fileName)
        if (!file.exists() || file.length() == 0L) return false
        val expectedSize = client.getContentLength(url) ?: return true
        return file.length() == expectedSize
    }

    private fun cleanupAndStop() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Data Transfer Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Background Transfers", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cleanupAndStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
