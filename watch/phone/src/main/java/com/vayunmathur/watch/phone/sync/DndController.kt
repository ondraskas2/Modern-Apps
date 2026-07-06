package com.vayunmathur.watch.phone.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.vayunmathur.watch.phone.ble.GattClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phone-side counterpart to the watch's DndController: mirrors the notification
 * interruption filter across the BLE link. Local changes are written to the
 * watch's DND characteristic; remote changes (collected from
 * [GattClientManager.remoteDnd]) are applied here. Same last-change-wins loop
 * guard: a filter we applied ourselves is not echoed back.
 */
class DndController(
    context: Context,
    private val client: GattClientManager,
) {
    private val appContext = context.applicationContext
    private val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Volatile private var lastAppliedFilter = -1
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                onLocalChanged()
            }
        }
    }

    fun register(scope: CoroutineScope) {
        if (registered) return
        appContext.registerReceiver(
            receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
        )
        registered = true
        scope.launch {
            client.remoteDnd.collect { applyRemote(it) }
        }
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    private fun applyRemote(filter: Int) {
        if (!nm.isNotificationPolicyAccessGranted) return
        if (filter == nm.currentInterruptionFilter) return
        lastAppliedFilter = filter
        runCatching { nm.setInterruptionFilter(filter) }
    }

    private fun onLocalChanged() {
        val filter = nm.currentInterruptionFilter
        if (filter == lastAppliedFilter) {
            lastAppliedFilter = -1
            return
        }
        client.writeDnd(filter)
    }
}
