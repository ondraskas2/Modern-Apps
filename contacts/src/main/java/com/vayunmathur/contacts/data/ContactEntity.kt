package com.vayunmathur.contacts.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey override val id: Long, // Maps to ContactsContract.RawContacts._ID
    val accountType: String?,
    val accountName: String?,
    val isFavorite: Boolean,
    val detailsJson: String // Serialized ContactDetails
) : DatabaseItem {
    fun toContact(): Contact {
        return Contact(
            id = id,
            accountType = accountType,
            accountName = accountName,
            isFavorite = isFavorite,
            details = Json.decodeFromString(detailsJson)
        )
    }

    companion object {
        fun fromContact(contact: Contact): ContactEntity {
            return ContactEntity(
                id = contact.id,
                accountType = contact.accountType,
                accountName = contact.accountName,
                isFavorite = contact.isFavorite,
                detailsJson = Json.encodeToString(contact.details)
            )
        }
    }
}

@Fts4
@Entity(tableName = "contacts_search")
data class ContactSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val displayName: String,
    val nickname: String,
    val phoneNumbers: String,
    val emails: String,
    val notes: String,
    val organization: String
)
