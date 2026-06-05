package com.vayunmathur.passwords.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordDao {
    @Query("SELECT * FROM Password")
    fun getAllFlow(): Flow<List<Password>>

    @Query("SELECT * FROM Password")
    suspend fun getAll(): List<Password>

    @Query("SELECT * FROM Password WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Password?>

    @Query("SELECT * FROM Password WHERE id = :id")
    suspend fun getById(id: Long): Password?

    @Upsert
    suspend fun upsert(value: Password): Long

    @Delete
    suspend fun delete(value: Password): Int
}

@Dao
interface PasskeyDao {
    @Query("SELECT * FROM Passkey")
    fun getAllFlow(): Flow<List<Passkey>>

    @Query("SELECT * FROM Passkey")
    suspend fun getAll(): List<Passkey>

    @Query("SELECT * FROM Passkey WHERE rpId = :rpId")
    suspend fun getByRpId(rpId: String): List<Passkey>

    @Query("SELECT * FROM Passkey WHERE credentialId = :credentialId")
    suspend fun getByCredentialId(credentialId: String): Passkey?

    @Upsert
    suspend fun upsert(passkey: Passkey): Long

    @Delete
    suspend fun delete(passkey: Passkey): Int
}

@Database(
    entities = [Password::class, Passkey::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class PasswordDatabase : RoomDatabase() {
    abstract fun passwordDao(): PasswordDao
    abstract fun passkeyDao(): PasskeyDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS `Passkey` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `rpId` TEXT NOT NULL,
                            `rpName` TEXT NOT NULL,
                            `credentialId` TEXT NOT NULL,
                            `userId` TEXT NOT NULL,
                            `userName` TEXT NOT NULL,
                            `userDisplayName` TEXT NOT NULL,
                            `privateKeyBytes` BLOB NOT NULL,
                            `creationTime` INTEGER NOT NULL,
                            `lastUsedTime` INTEGER NOT NULL,
                            `signCount` INTEGER NOT NULL
                        )"""
                    )
                }
            }
        )
    }
}
