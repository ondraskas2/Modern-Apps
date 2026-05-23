package com.vayunmathur.contacts.util

import android.content.Context
import android.content.pm.PackageManager

object PackageUtils {
    const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
    const val WHATSAPP_PACKAGE = "com.whatsapp"

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isSignalInstalled(context: Context) = isAppInstalled(context, SIGNAL_PACKAGE)
    fun isWhatsAppInstalled(context: Context) = isAppInstalled(context, WHATSAPP_PACKAGE)
}
