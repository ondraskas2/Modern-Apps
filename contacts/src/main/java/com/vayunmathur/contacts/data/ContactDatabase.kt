package com.vayunmathur.contacts.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY id ASC")
    fun getContactsFlow(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getContactById(id: Long): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteContacts(ids: List<Long>)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    @Transaction
    suspend fun syncContacts(toUpsert: List<ContactEntity>, toDelete: List<Long>, searchEntities: List<ContactSearchEntity>) {
        if (toDelete.isNotEmpty()) {
            deleteContacts(toDelete)
            // The FTS table is not content-backed, so deletes from 'contacts' do not
            // cascade — remove the matching search rows in the same transaction.
            deleteSearchEntities(toDelete)
        }
        if (toUpsert.isNotEmpty()) {
            insertContacts(toUpsert)
            insertSearchEntities(searchEntities)
        }
    }

    @Query("DELETE FROM contacts_search WHERE rowid IN (:ids)")
    suspend fun deleteSearchEntities(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchEntities(entities: List<ContactSearchEntity>)

    @Query("""
        SELECT contacts.* FROM contacts 
        JOIN contacts_search ON contacts.id = contacts_search.rowid 
        WHERE contacts_search MATCH :query
    """)
    fun search(query: String): Flow<List<ContactEntity>>
}

@Database(entities = [ContactEntity::class, ContactSearchEntity::class], version = 3, exportSchema = false)
abstract class ContactDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var INSTANCE: ContactDatabase? = null

        fun getInstance(context: Context): ContactDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ContactDatabase::class.java,
                "contacts_database"
            ).build().also { INSTANCE = it }
        }
    }
}
