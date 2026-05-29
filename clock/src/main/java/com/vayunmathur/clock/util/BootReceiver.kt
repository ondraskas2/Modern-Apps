package com.vayunmathur.clock.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val db = context.buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
            val scheduler = AlarmScheduler.get()
            
            CoroutineScope(Dispatchers.IO).launch {
                val alarms = db.alarmDao().getAll()
                alarms.forEach { alarm ->
                    if (alarm.enabled) {
                        scheduler.schedule(context, alarm)
                    }
                }
            }
        }
    }
}
