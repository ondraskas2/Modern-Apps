package com.vayunmathur.launcher.util

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        updateBadgeCounts()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        updateBadgeCounts()
    }

    private fun updateBadgeCounts() {
        try {
            val notifications = activeNotifications ?: return
            val counts = mutableMapOf<String, Int>()
            for (sbn in notifications) {
                if (sbn.isGroup && sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
                    continue
                }
                val pkg = sbn.packageName
                counts[pkg] = (counts[pkg] ?: 0) + 1
            }
            _badgeCounts.value = counts
        } catch (_: Exception) { }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        updateBadgeCounts()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    companion object {
        private var instance: NotificationListener? = null

        private val _badgeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val badgeCounts: StateFlow<Map<String, Int>> = _badgeCounts.asStateFlow()
    }
}
