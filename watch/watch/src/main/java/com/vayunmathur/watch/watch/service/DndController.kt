package com.vayunmathur.watch.watch.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.vayunmathur.watch.watch.ble.GattServerManager

/**
 * Bridges the watch's Do-Not-Disturb (notification interruption filter) with the
 * BLE DND characteristic, so a change on either device propagates to the other.
 *
 * Loop guard: whenever we apply a *remote* filter we record it in
 * [lastAppliedFilter]; the resulting ACTION_INTERRUPTION_FILTER_CHANGED broadcast
 * is then recognized as our own echo and not pushed back out. Applying the filter
 * requires notification-policy access; reading/observing it does not.
 */
class DndController(
    context: Context,
    private val gattServer: GattServerManager,
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

    fun register() {
        if (registered) return
        appContext.registerReceiver(
            receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
        )
        registered = true
        // Publish the current filter once so a freshly connected phone can sync.
        gattServer.pushLocalDnd(nm.currentInterruptionFilter)
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    /** Applies a filter received from the phone, unless it is our own echo. */
    fun onRemoteDnd(filter: Int) {
        if (!nm.isNotificationPolicyAccessGranted) return
        if (filter == nm.currentInterruptionFilter) return
        lastAppliedFilter = filter
        runCatching { nm.setInterruptionFilter(filter) }
    }

    private fun onLocalChanged() {
        val filter = nm.currentInterruptionFilter
        // Skip the echo from our own setInterruptionFilter call.
        if (filter == lastAppliedFilter) {
            lastAppliedFilter = -1
            return
        }
        gattServer.pushLocalDnd(filter)
    }
}
