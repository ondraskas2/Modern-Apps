package com.vayunmathur.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.ContactsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Screenshot generator driven by `:contacts:metadata`. Seeds sample contacts (with one
 * rich profile) and groups into the system ContactsProvider before launch, waits for the
 * app's provider->Room sync, then captures the list, a rich detail page, and the groups page.
 *
 * `pm clear` does NOT wipe the system ContactsProvider, so we delete previously-seeded rows
 * at the start of every run to keep reruns idempotent.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** Removes contacts and groups left over from a previous run so reruns don't duplicate. */
    private fun clearExisting() {
        val resolver = ctx.contentResolver
        resolver.delete(ContactsContract.RawContacts.CONTENT_URI, null, null)
        resolver.delete(ContactsContract.Groups.CONTENT_URI, null, null)
    }

    /** Draws a simple flat-color avatar with a centered initial and returns PNG bytes. */
    private fun avatarPng(letter: String, color: Int): ByteArray {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, baseline, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun addGroup(title: String): Long {
        val values = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, title)
            put(ContactsContract.Groups.GROUP_VISIBLE, 1)
        }
        return ContentUris.parseId(
            ctx.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, values)!!
        )
    }

    private data class Person(
        val name: String,
        val phone: String,
        val groupIds: List<Long> = emptyList(),
    )

    private fun insertPerson(person: Person) {
        val ops = arrayListOf<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, person.name)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, person.phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )
        for (gid in person.groupIds) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, gid)
                    .build()
            )
        }
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    /** Inserts one contact with a photo, email, phone, birthday, organization and group. */
    private fun insertRichPerson(name: String, groupIds: List<Long>) {
        val ops = arrayListOf<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, name.substringBefore(' '))
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, name.substringAfter(' '))
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, avatarPng(name.take(1), Color.parseColor("#6750A4")))
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "+1 415 555 0102")
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, "aisha.rahman@example.com")
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, "1992-03-15")
                .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, "Acme Design Studio")
                .build()
        )
        for (gid in groupIds) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, gid)
                    .build()
            )
        }
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun seed() {
        clearExisting()

        val family = addGroup("Family")
        val friends = addGroup("Friends")
        val work = addGroup("Work")

        // Rich contact leads alphabetically so it's visible without scrolling.
        insertRichPerson("Aisha Rahman", listOf(friends, work))

        val people = listOf(
            Person("Aaron Blake", "+1 415 555 0142", listOf(family)),
            Person("Amelia Brooks", "+1 415 555 0173", listOf(family, friends)),
            Person("Benjamin Carter", "+1 628 555 0119", listOf(work)),
            Person("Bianca Lopez", "+1 510 555 0188", listOf(friends)),
            Person("Chloe Nguyen", "+1 415 555 0164", listOf(friends)),
            Person("Daniel Osei", "+1 650 555 0127"),
            Person("Emma Rossi", "+1 415 555 0150", listOf(work)),
            Person("Grace Kim", "+1 408 555 0136", listOf(family)),
        )
        for (p in people) insertPerson(p)
    }

    @Test
    fun generateStoreScreenshots() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            // Wait for the debounced provider->Room sync to surface the seeded contacts.
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("Aisha Rahman").fetchSemanticsNodes().isNotEmpty()
            }

            // 1: contact list showing alphabetical section grouping.
            snap(1)

            // 3: groups page (via bottom nav).
            composeRule.onNodeWithText("Groups").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1500)
            snap(3)

            // 2: rich contact detail page. Return to the list, then open the contact.
            // (The detail page has no bottom nav, so capture it last.)
            composeRule.onNodeWithText("Contacts").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Aisha Rahman").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1500)
            snap(2)
        }
    }
}
