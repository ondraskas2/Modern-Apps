package com.vayunmathur.email.util

/**
 * Simple process-wide flag indicating whether the email app is currently in
 * the foreground (any activity at or above STARTED). Set from `MainActivity`'s
 * `onStart` / `onStop`. The sync worker reads this to suppress notifications
 * when the user is actively viewing the app.
 */
object AppLifecycleTracker {
    @Volatile
    var isAppInForeground: Boolean = false
}
