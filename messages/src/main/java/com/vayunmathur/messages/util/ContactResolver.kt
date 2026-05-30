package com.vayunmathur.messages.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Resolves a phone number to a device-contact display name + photo URI.
 *
 * Backed by [ContactsContract.PhoneLookup], which Android's contact
 * subsystem queries via the same E.164/national fuzzy match the system
 * Phone / Messages apps use. Returns null when the permission is missing
 * or no contact matches.
 *
 * Why we use this even though Google Messages already sends display
 * names: those names come from the user's Google contacts (which can
 * lag, conflict, or be empty for numbers not in Google contacts). The
 * device's contact database is canonical for the user's intent — we
 * always prefer it.
 */
object ContactResolver {

    private const val TAG = "ContactResolver"

    data class Result(
        val displayName: String?,
        /** content:// URI suitable for Coil/Glide loading. */
        val photoUri: String?,
    )

    /** Search hit returned by [search]. Phones are stored verbatim from
     *  the Contacts provider — they may or may not be in E.164. The UI
     *  is responsible for normalization before sending. */
    data class SearchHit(
        val displayName: String,
        val phoneE164: String,
        val photoUri: String?,
    )

    /**
     * Search device contacts by name or phone substring.
     *
     * Backed by [ContactsContract.Contacts.CONTENT_FILTER_URI] for name
     * matches and [ContactsContract.CommonDataKinds.Phone.CONTENT_URI]
     * for number matches. Returns up to [limit] suggestions, sorted
     * with starred contacts first (matches the system Phone app order).
     */
    fun search(context: Context, query: String, limit: Int = 30): List<SearchHit> {
        if (!hasPermission(context)) return emptyList()
        if (query.isBlank()) return topContacts(context, limit)
        val out = mutableListOf<SearchHit>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        // Match either the display name OR the phone number against the
        // user's query. NORMALIZED_NUMBER strips punctuation so a search
        // for "5551234" matches "+1 (555) 123-4" too.
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val wildcard = "%${query.trim()}%"
        val args = arrayOf(wildcard, wildcard, wildcard)
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val phone = (cursor.getString(1)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(2)?.takeIf { it.isNotBlank() }) ?: continue
                    val photo = cursor.getString(3)?.takeIf { it.isNotBlank() }
                    out += SearchHit(displayName = name, phoneE164 = phone, photoUri = photo)
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "search($query) failed: ${t.message}")
            out
        }
    }

    /** Subset of [search] for the "initial suggestions" state when the
     *  user hasn't typed anything yet. Returns starred contacts first. */
    private fun topContacts(context: Context, limit: Int): List<SearchHit> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        )
        val out = mutableListOf<SearchHit>()
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $limit",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val phone = (cursor.getString(1)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(2)?.takeIf { it.isNotBlank() }) ?: continue
                    val photo = cursor.getString(3)?.takeIf { it.isNotBlank() }
                    out += SearchHit(displayName = name, phoneE164 = phone, photoUri = photo)
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "topContacts() failed: ${t.message}")
            out
        }
    }

    fun lookup(context: Context, phoneNumber: String): Result? {
        if (phoneNumber.isBlank()) return null
        if (!hasPermission(context)) return null
        // PHONE_LOOKUP is the right URI for "I have a phone number and
        // want the contact" — handles per-region number normalization
        // internally so "+1 555-555-1234" and "5555551234" both match.
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
            ContactsContract.PhoneLookup.PHOTO_URI,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                // Prefer the full PHOTO_URI; fall back to thumbnail. Both
                // are content:// URIs that Coil can render.
                val photo = cursor.getString(2)?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(1)?.takeIf { it.isNotBlank() }
                Result(displayName = name, photoUri = photo)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PhoneLookup for $phoneNumber failed: ${t.message}")
            null
        }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}
