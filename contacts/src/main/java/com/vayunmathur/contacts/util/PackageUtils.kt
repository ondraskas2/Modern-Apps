package com.vayunmathur.contacts.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log

object PackageUtils {
    const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    const val GOOGLE_MEET_PACKAGE = "com.google.android.apps.tachyon"

    private const val TAG = "ContactPlatforms"

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
    fun isTelegramInstalled(context: Context) = isAppInstalled(context, TELEGRAM_PACKAGE)
    fun isGoogleMeetInstalled(context: Context) = isAppInstalled(context, GOOGLE_MEET_PACKAGE)

    private fun getAggregateContactId(context: Context, rawContactId: Long): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting aggregate contact ID", e)
            null
        }
    }

    fun getContactPlatforms(context: Context, rawContactId: Long): ContactPlatforms {
        val aggregateContactId = getAggregateContactId(context, rawContactId)
            ?: return ContactPlatforms()

        var result = ContactPlatforms()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.MIMETYPE
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND (" +
                    "${ContactsContract.Data.MIMETYPE} LIKE ? OR " +
                    "${ContactsContract.Data.MIMETYPE} LIKE ? OR " +
                    "${ContactsContract.Data.MIMETYPE} LIKE ?)",
                arrayOf(aggregateContactId.toString(), "%whatsapp%", "%securesms%", "%telegram%"),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val mime = cursor.getString(mimeIdx) ?: continue
                    result = when {
                        mime.contains("whatsapp") && mime.contains("voip") -> result.copy(whatsAppCallId = id)
                        mime.contains("whatsapp") && mime.contains("video") -> result.copy(whatsAppVideoId = id)
                        mime.contains("whatsapp") && (mime.contains("profile") || mime.contains("contact")) -> result.copy(whatsAppMessageId = id)
                        mime.contains("securesms") && mime.contains("video") -> result.copy(signalVideoId = id)
                        mime.contains("securesms") && mime.contains("call") -> result.copy(signalCallId = id)
                        mime.contains("securesms") && (mime.contains("contact") || mime.contains("profile")) -> result.copy(signalMessageId = id)
                        mime.contains("telegram") && mime.contains("video") -> result.copy(telegramVideoId = id)
                        mime.contains("telegram") && mime.contains("call") -> result.copy(telegramCallId = id)
                        mime.contains("telegram") && (mime.contains("profile") || mime.contains("contact")) -> result.copy(telegramMessageId = id)
                        else -> result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying platform data rows", e)
        }

        return result
    }
}

data class ContactPlatforms(
    val whatsAppCallId: Long? = null,
    val whatsAppVideoId: Long? = null,
    val whatsAppMessageId: Long? = null,
    val signalCallId: Long? = null,
    val signalVideoId: Long? = null,
    val signalMessageId: Long? = null,
    val telegramCallId: Long? = null,
    val telegramVideoId: Long? = null,
    val telegramMessageId: Long? = null,
) {
    val hasWhatsApp get() = whatsAppCallId != null || whatsAppVideoId != null || whatsAppMessageId != null
    val hasSignal get() = signalCallId != null || signalVideoId != null || signalMessageId != null
    val hasTelegram get() = telegramCallId != null || telegramVideoId != null || telegramMessageId != null
    val hasAnyPlatform get() = hasWhatsApp || hasSignal || hasTelegram
}
