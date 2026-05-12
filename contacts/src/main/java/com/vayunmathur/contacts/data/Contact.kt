package com.vayunmathur.contacts.data
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Profile
import android.util.Log
import androidx.core.database.getBlobOrNull
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

val LocalDate.hasYear: Boolean get() = year >= 1901

fun LocalDate.formatDisplay(): String {
    return if (hasYear) {
        format(LocalDate.Format {
            monthName(MonthNames.ENGLISH_FULL)
            chars(" ")
            day()
            chars(", ")
            year()
        })
    } else {
        format(LocalDate.Format {
            monthName(MonthNames.ENGLISH_FULL)
            chars(" ")
            day()
        })
    }
}


@Serializable
data class ContactDetails(
    val phoneNumbers: List<PhoneNumber>,
    val emails: List<Email>,
    val addresses: List<Address>,
    val dates: List<Event>,
    val photos: List<Photo>,
    val names: List<Name>,
    val orgs: List<Organization>,
    val notes: List<Note>,
    val nicknames: List<Nickname>
) {
    fun all(): List<ContactDetail<*>> {
        return phoneNumbers + emails + addresses + dates + photos + names + orgs + notes + nicknames
    }

    companion object {
        fun empty(): ContactDetails {
            return ContactDetails(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }
}

typealias CDKEmail = ContactsContract.CommonDataKinds.Email
typealias CDKPhone = ContactsContract.CommonDataKinds.Phone
typealias CDKStructuredPostal = ContactsContract.CommonDataKinds.StructuredPostal
typealias CDKEvent = ContactsContract.CommonDataKinds.Event
typealias CDKPhoto = ContactsContract.CommonDataKinds.Photo
typealias CDKSName = ContactsContract.CommonDataKinds.StructuredName
typealias CDKOrg = ContactsContract.CommonDataKinds.Organization
typealias CDKNote = ContactsContract.CommonDataKinds.Note
typealias CDKNickname = ContactsContract.CommonDataKinds.Nickname

interface ContactDetail<T: ContactDetail<T>> {
    val id: Long
    val type: Int
    val value: String
    fun withType(type: Int): T
    fun withValue(value: String): T
    fun typeString(context: Context): String

    companion object {
        @OptIn(ExperimentalTime::class)
        inline fun <reified T: ContactDetail<T>> default(): T {
            return when (T::class) {
                PhoneNumber::class -> PhoneNumber(0, "", CDKPhone.TYPE_MOBILE)
                Email::class -> Email(0, "", CDKEmail.TYPE_HOME)
                Address::class -> Address(0, "", CDKStructuredPostal.TYPE_HOME)
                Event::class -> Event(0, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date, CDKEvent.TYPE_OTHER)
                else -> throw IllegalArgumentException("Unknown type")
            } as T
        }
    }
}

@Serializable
data class PhoneNumber(override val id: Long, val number: String, override val type: Int): ContactDetail<PhoneNumber> {
    override val value: String
        get() = number

    override fun withType(type: Int) = PhoneNumber(id, number, type)
    override fun withValue(value: String) = PhoneNumber(id, value, type)

    override fun typeString(context: Context) = CDKPhone.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Email(override val id: Long, val address: String, override val type: Int): ContactDetail<Email> {
    override val value: String
        get() = address

    override fun withType(type: Int) = Email(id, address, type)
    override fun withValue(value: String) = Email(id, value, type)

    override fun typeString(context: Context) = CDKEmail.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Address(override val id: Long, val formattedAddress: String, override val type: Int): ContactDetail<Address> {
    override val value: String
        get() = formattedAddress

    override fun withType(type: Int) = Address(id, formattedAddress, type)
    override fun withValue(value: String) = Address(id, value, type)

    override fun typeString(context: Context) = CDKStructuredPostal.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Photo(override val id: Long, val photo: String): ContactDetail<Photo> {
    override val type: Int = 0
    override val value: String
        get() = photo

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of photo")
    override fun withValue(value: String) = Photo(id, value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Photo doesn't have type")
}

@Serializable
data class Event(override val id: Long, val startDate: LocalDate, override val type: Int): ContactDetail<Event> {
    override val value: String
        get() = startDate.format(LocalDate.Formats.ISO)

    override fun withType(type: Int) = Event(id, startDate, type)
    override fun withValue(value: String) = Event(id, LocalDate.parse(value), type)

    override fun typeString(context: Context) = CDKEvent.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Organization(override val id: Long, val company: String): ContactDetail<Organization> {
    override val type: Int = 0
    override val value: String
        get() = company

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of photo")
    override fun withValue(value: String) = Organization(id, value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Photo doesn't have type")
}

@Serializable
data class Name(
    override val id: Long,
    val namePrefix: String,
    val firstName: String,
    val middleName: String,
    val lastName: String,
    val nameSuffix: String
): ContactDetail<Name> {
    override val type: Int = 0
    override val value: String
        get() = listOfNotNull(namePrefix.ifEmpty { null }, firstName.ifEmpty { null }, middleName.ifEmpty { null }, lastName.ifEmpty { null }, nameSuffix.ifEmpty { null }).joinToString(" ")

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of name")
    override fun withValue(value: String) = throw UnsupportedOperationException("Cannot change value of name")

    override fun typeString(context: Context) = throw UnsupportedOperationException("Name doesn't have type")
}

@Serializable
data class Note(override val id: Long, val content: String): ContactDetail<Note> {
    override val type: Int = 0
    override val value: String
        get() = content

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of note")
    override fun withValue(value: String) = copy(content = value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Note doesn't have type")
}

@Serializable
data class Nickname(override val id: Long, val nickname: String, override val type: Int): ContactDetail<Nickname> {
    override val value: String
        get() = nickname

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(nickname = value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Nickname types shouldn't be written")
}

@Serializable
data class Contact(
    val id: Long,
    val accountType: String?,
    val accountName: String?,
    val isFavorite: Boolean,
    val details: ContactDetails
) {
    val name: Name
        get() = details.names.first()

    val photo: Photo?
        get() = details.photos.firstOrNull()

    val org: Organization
        get() = details.orgs.first()

    val nickname: Nickname
        get() = details.nicknames.first { it.type == CDKNickname.TYPE_DEFAULT }

    val birthday: Event?
        get() = details.dates.firstOrNull { it.type == CDKEvent.TYPE_BIRTHDAY }

    val note: Note
        get() = details.notes.first()

    fun save(context: Context, newDetails: ContactDetails, oldDetails: ContactDetails) {
        val ops = ArrayList<ContentProviderOperation>()
        if (id == 0L) {
            val builder = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            if (accountType != null && accountName != null) {
                builder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                builder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            } else {
                builder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                builder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            }
            ops += builder.build()

            ops += details.all().map { createInsertOperation(it) }
        } else {
            // Favorite
            ops += ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.Contacts._ID} = ?", arrayOf(id.toString()))
                .withValue(ContactsContract.RawContacts.STARRED, if (isFavorite) 1 else 0)
                .build()

            // details
            ops += handleDetailUpdates(oldDetails.all(), newDetails.all(), id.toString())
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun handleDetailUpdates(currentDetails: List<ContactDetail<*>>, newDetails: List<ContactDetail<*>>, rawContactID: String): List<ContentProviderOperation> {
        val currentIds = currentDetails.map { it.id }.toSet()
        val newIds = newDetails.map { it.id }.toSet()
        val ops = mutableListOf<ContentProviderOperation>()

        val idsToDelete = currentIds - newIds
        ops += idsToDelete.map { id -> createDeleteOperation(id)  }

        ops += newDetails.mapNotNull { detail ->
            if (detail.id == 0L) { // New item
                createInsertOperation(detail, rawContactID)
            } else { // Existing item, check if it has changed
                val oldDetail = currentDetails.find { it.id == detail.id }
                if (oldDetail != null && oldDetail != detail) {
                    createUpdateOperation(detail)
                } else null
            }
        }

        return ops
    }

    private fun createInsertOperation(detail: ContactDetail<*>, rawContactId: String? = null): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        if (rawContactId != null) {
            builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
        } else {
            builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        }

        return builder.completeOperation(detail, true)
    }

    private fun createUpdateOperation(detail: ContactDetail<*>): ContentProviderOperation {
        return ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(detail.id.toString()))
            .completeOperation(detail, false)
    }

    private fun createDeleteOperation(dataId: Long): ContentProviderOperation {
        return ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId.toString()))
            .build()
    }

    fun ContentProviderOperation.Builder.completeOperation(detail: ContactDetail<*>, isInsert: Boolean): ContentProviderOperation {
        if (isInsert) {
            this.withValue(ContactsContract.Data.MIMETYPE, when(detail) {
                is PhoneNumber -> CDKPhone.CONTENT_ITEM_TYPE
                is Email -> CDKEmail.CONTENT_ITEM_TYPE
                is Address -> CDKStructuredPostal.CONTENT_ITEM_TYPE
                is Event -> CDKEvent.CONTENT_ITEM_TYPE
                is Photo -> CDKPhoto.CONTENT_ITEM_TYPE
                is Name -> CDKSName.CONTENT_ITEM_TYPE
                is Organization -> CDKOrg.CONTENT_ITEM_TYPE
                is Note -> CDKNote.CONTENT_ITEM_TYPE
                is Nickname -> CDKNickname.CONTENT_ITEM_TYPE
                else -> throw IllegalArgumentException("Unknown detail type")
            })
        }
        return when (detail) {
            is PhoneNumber -> this
                .withValue(CDKPhone.NUMBER, detail.number)
                .withValue(CDKPhone.TYPE, detail.type)
                .build()
            is Email -> this
                .withValue(CDKEmail.ADDRESS, detail.address)
                .withValue(CDKEmail.TYPE, detail.type)
                .build()
            is Address -> this
                .withValue(CDKStructuredPostal.FORMATTED_ADDRESS, detail.formattedAddress)
                .withValue(CDKStructuredPostal.TYPE, detail.type)
                .build()
            is Event -> this
                .withValue(CDKEvent.START_DATE, detail.startDate.format(LocalDate.Formats.ISO))
                .withValue(CDKEvent.TYPE, detail.type)
                .build()
            is Photo -> this
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(CDKPhoto.PHOTO, Base64.decode(detail.photo))
                .build()
            is Name -> this
                .withValue(CDKSName.PREFIX, detail.namePrefix)
                .withValue(CDKSName.GIVEN_NAME, detail.firstName)
                .withValue(CDKSName.MIDDLE_NAME, detail.middleName)
                .withValue(CDKSName.FAMILY_NAME, detail.lastName)
                .withValue(CDKSName.SUFFIX, detail.nameSuffix)
                .build()
            is Organization -> this
                .withValue(CDKOrg.COMPANY, detail.company)
                .build()
            is Note -> this
                .withValue(CDKNote.NOTE, detail.content)
                .build()
            is Nickname -> this
                .withValue(CDKNickname.NAME, detail.nickname)
                .withValue(CDKNickname.TYPE, detail.type)
                .build()

            else -> throw IllegalArgumentException("Unknown detail type")
        }
    }

    companion object {

        private fun processDetails(details: ContactDetails, displayName: String?): ContactDetails? {
            var details = details
            if(details.names.isEmpty())
                details = details.copy(names = listOf(Name(0, "", "", "", "", "")))

            if((details.names.first().firstName.isEmpty() && details.names.first().lastName.isEmpty())) {
                if(displayName == null) return null
                val firstName = displayName.split(" ").first()
                val lastName = displayName.split(" ").last()
                if(firstName.isEmpty() && lastName.isEmpty()) return null
                details = details.copy(names = listOf(Name(details.names.first().id, "", firstName, "", lastName, "")))
            }

            if(details.orgs.isEmpty())
                details = details.copy(orgs = listOf(Organization(0, "")))

            if(details.notes.isEmpty())
                details = details.copy(notes = listOf(Note(0, "")))

            if(details.nicknames.find { it.type == CDKNickname.TYPE_DEFAULT } == null)
                details = details.copy(nicknames = details.nicknames + Nickname(0, "", CDKNickname.TYPE_DEFAULT))

            return details
        }

        private data class RawContactInfo(
            val id: Long,
            val displayName: String?,
            val isFavorite: Boolean,
            val accountName: String?,
            val accountType: String?
        )

        private fun getContacts(context: Context, contactId: Long?): List<Contact> {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.STARRED,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
            )
            val contacts = mutableListOf<Contact>()

            val rawContacts = mutableListOf<RawContactInfo>()
            try {
                val cursor = contentResolver.query(uri, projection, if(contactId == null) null else "${ContactsContract.Contacts._ID} = ?", listOfNotNull(contactId?.toString()).toTypedArray(), null)

                cursor?.use {
                    val idIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                    val nameIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY)
                    val starredIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts.STARRED)
                    val accountNameIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
                    val accountTypeIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)

                    while (it.moveToNext()) {
                        try {
                            rawContacts.add(RawContactInfo(
                                id = it.getLong(idIdx),
                                displayName = it.getStringOrNull(nameIdx),
                                isFavorite = it.getInt(starredIdx) == 1,
                                accountName = it.getStringOrNull(accountNameIdx),
                                accountType = it.getStringOrNull(accountTypeIdx)
                            ))
                        } catch (e: Exception) {
                            Log.e("Contact", "Error constructing raw contact info from cursor", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Contact", "Error querying contacts", e)
            }

            if (rawContacts.isEmpty()) return emptyList()

            // Fetch details for all fetched raw contacts
            val allDetails = getDetailsInternal(context, contactId)

            for (raw in rawContacts) {
                var details = allDetails[raw.id] ?: ContactDetails.empty()
                details = processDetails(details, raw.displayName) ?: continue

                contacts += Contact(raw.id, raw.accountType, raw.accountName, raw.isFavorite, details)
            }
            return contacts
        }

        fun getContact(context: Context, contactId: Long): Contact? = getContacts(context, contactId).firstOrNull()

        fun getAllContacts(context: Context): List<Contact> =
            getContacts(context, null)

        fun delete(context: Context, contact: Contact) {
            context.contentResolver.delete(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, contact.id),
                null, null
            )
        }
    }
}

fun getDetails(context: Context, id: Long, isProfile: Boolean = false): ContactDetails {
    return getDetailsInternal(context, id, isProfile)[id] ?: ContactDetails.empty()
}

fun getDetailsInternal(context: Context, id: Long? = null, isProfile: Boolean = false): Map<Long, ContactDetails> {
    val contentResolver = context.contentResolver

    val projection = arrayOf(
        ContactsContract.Data.RAW_CONTACT_ID,
        ContactsContract.Data._ID,
        ContactsContract.Data.MIMETYPE,
        ContactsContract.Data.DATA1,
        ContactsContract.Data.DATA2,
        ContactsContract.Data.DATA3,
        ContactsContract.Data.DATA4,
        ContactsContract.Data.DATA5,
        ContactsContract.Data.DATA6,
        ContactsContract.Data.DATA7,
        ContactsContract.Data.DATA8,
        ContactsContract.Data.DATA9,
        ContactsContract.Data.DATA10,
        ContactsContract.Data.DATA15
    )

    val phoneNumbersMap = mutableMapOf<Long, MutableList<PhoneNumber>>()
    val emailsMap = mutableMapOf<Long, MutableList<Email>>()
    val addressesMap = mutableMapOf<Long, MutableList<Address>>()
    val datesMap = mutableMapOf<Long, MutableList<Event>>()
    val photosMap = mutableMapOf<Long, MutableList<Photo>>()
    val namesMap = mutableMapOf<Long, MutableList<Name>>()
    val orgsMap = mutableMapOf<Long, MutableList<Organization>>()
    val notesMap = mutableMapOf<Long, MutableList<Note>>()
    val nicknamesMap = mutableMapOf<Long, MutableList<Nickname>>()

    val rawContactIds = mutableSetOf<Long>()

    try {
        contentResolver.query(
            if (isProfile) Uri.withAppendedPath(Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY) else ContactsContract.Data.CONTENT_URI,
            projection,
            if (id != null) "${ContactsContract.Data.RAW_CONTACT_ID} = ?" else null,
            if (id != null) arrayOf(id.toString()) else null,
            null
        )?.use { cursor ->
            val rawIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val d1Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            val d2Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
            val d3Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
            val d4Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
            val d5Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA5)
            val d6Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA6)
            val d7Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA7)
            val d8Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA8)
            val d9Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA9)
            val d10Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA10)
            val d15Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA15)

            while (cursor.moveToNext()) {
                try {
                    val rawId = cursor.getLong(rawIdIdx)
                    rawContactIds.add(rawId)
                    val dataId = cursor.getLong(idIdx)
                    when (cursor.getString(mimeIdx)) {
                        CDKPhone.CONTENT_ITEM_TYPE -> {
                            val number = cursor.getStringOrNull(d1Idx) ?: ""
                            val type = cursor.getInt(d2Idx)
                            phoneNumbersMap.getOrPut(rawId) { mutableListOf() }.add(PhoneNumber(dataId, number, type))
                        }
                        CDKEmail.CONTENT_ITEM_TYPE -> {
                            val address = cursor.getStringOrNull(d1Idx) ?: ""
                            val type = cursor.getInt(d2Idx)
                            emailsMap.getOrPut(rawId) { mutableListOf() }.add(Email(dataId, address, type))
                        }
                        CDKStructuredPostal.CONTENT_ITEM_TYPE -> {
                            var formatted = cursor.getStringOrNull(d1Idx)
                            val type = cursor.getInt(d2Idx)
                            if (formatted.isNullOrBlank()) {
                                val street = cursor.getStringOrNull(d4Idx)
                                val city = cursor.getStringOrNull(d7Idx)
                                val region = cursor.getStringOrNull(d8Idx)
                                val code = cursor.getStringOrNull(d9Idx)
                                val country = cursor.getStringOrNull(d10Idx)
                                formatted = listOfNotNull(street, city, region, code, country)
                                    .filter { it.isNotBlank() }
                                    .joinToString(", ")
                            }
                            addressesMap.getOrPut(rawId) { mutableListOf() }.add(Address(dataId, formatted, type))
                        }
                        CDKEvent.CONTENT_ITEM_TYPE -> {
                            val date = cursor.getStringOrNull(d1Idx) ?: ""
                            val type = cursor.getInt(d2Idx)
                            val localDate = if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                                LocalDate.parse(date, LocalDate.Formats.ISO)
                            } else if (date.matches(Regex("\\d{8}"))) {
                                LocalDate.parse(date, LocalDate.Format { year(); monthNumber(); day() })
                            } else if (date.startsWith("--")) {
                                try {
                                    LocalDate.parse("1604" + date.substring(2))
                                } catch (_: Exception) { null }
                            } else null

                            if (localDate != null) {
                                datesMap.getOrPut(rawId) { mutableListOf() }.add(Event(dataId, localDate, type))
                            }
                        }
                        CDKPhoto.CONTENT_ITEM_TYPE -> {
                            val photoBlob = cursor.getBlobOrNull(d15Idx)
                            if (photoBlob != null) {
                                photosMap.getOrPut(rawId) { mutableListOf() }.add(Photo(dataId, Base64.encode(photoBlob)))
                            }
                        }
                        CDKSName.CONTENT_ITEM_TYPE -> {
                            val prefix = cursor.getStringOrNull(d4Idx) ?: ""
                            val given = cursor.getStringOrNull(d2Idx) ?: ""
                            val middle = cursor.getStringOrNull(d5Idx) ?: ""
                            val family = cursor.getStringOrNull(d3Idx) ?: ""
                            val suffix = cursor.getStringOrNull(d6Idx) ?: ""
                            namesMap.getOrPut(rawId) { mutableListOf() }.add(Name(dataId, prefix, given, middle, family, suffix))
                        }
                        CDKOrg.CONTENT_ITEM_TYPE -> {
                            val company = cursor.getStringOrNull(d1Idx) ?: ""
                            orgsMap.getOrPut(rawId) { mutableListOf() }.add(Organization(dataId, company))
                        }
                        CDKNote.CONTENT_ITEM_TYPE -> {
                            val noteContent = cursor.getStringOrNull(d1Idx) ?: ""
                            notesMap.getOrPut(rawId) { mutableListOf() }.add(Note(dataId, noteContent))
                        }
                        CDKNickname.CONTENT_ITEM_TYPE -> {
                            val nickname = cursor.getStringOrNull(d1Idx) ?: ""
                            val type = cursor.getInt(d2Idx)
                            nicknamesMap.getOrPut(rawId) { mutableListOf() }.add(Nickname(dataId, nickname, type))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Contact", "Error constructing contact detail from cursor", e)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Contact", "Error querying contact details", e)
    }

    return rawContactIds.associateWith { rawId ->
        ContactDetails(
            phoneNumbersMap[rawId]?.distinct() ?: emptyList(),
            emailsMap[rawId]?.distinct() ?: emptyList(),
            addressesMap[rawId]?.distinct() ?: emptyList(),
            datesMap[rawId]?.distinct() ?: emptyList(),
            photosMap[rawId]?.distinct() ?: emptyList(),
            namesMap[rawId]?.distinct() ?: emptyList(),
            orgsMap[rawId]?.distinct() ?: emptyList(),
            notesMap[rawId]?.distinct() ?: emptyList(),
            nicknamesMap[rawId]?.distinct() ?: emptyList()
        )
    }
}
