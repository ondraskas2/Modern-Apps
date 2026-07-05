package com.vayunmathur.watch.phone.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.watch.phone.R
import com.vayunmathur.watch.phone.ble.ConnectionState
import com.vayunmathur.watch.phone.ble.GattClientManager
import com.vayunmathur.watch.phone.health.HealthConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that owns the BLE link. It drives the
 * [GattClientManager], keeps re-scanning when disconnected, and writes each
 * received+acked batch to Health Connect via [HealthConnectManager].
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var client: GattClientManager
    private lateinit var health: HealthConnectManager

    override fun onCreate() {
        super.onCreate()
        client = GattClientManager(this)
        health = HealthConnectManager(this)

        scope.launch {
            client.state.collect { state ->
                connectionState.value = state
                // Auto-reconnect whenever the link drops.
                if (state == ConnectionState.Disconnected) {
                    client.startScan()
                }
            }
        }

        scope.launch {
            client.batches.collect { records ->
                health.insert(records)
                syncedCount.value += records.size
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        client.startScan()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        client.disconnect()
        scope.cancel()
        connectionState.value = ConnectionState.Disconnected
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "watch_sync"
        private const val NOTIFICATION_ID = 1

        val connectionState = MutableStateFlow(ConnectionState.Disconnected)
        val syncedCount = MutableStateFlow(0)

        val connectionStateFlow: StateFlow<ConnectionState> = connectionState
        val syncedCountFlow: StateFlow<Int> = syncedCount

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
