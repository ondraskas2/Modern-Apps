package com.vayunmathur.library.util

import android.content.Context
import java.io.File
import java.io.FileInputStream
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant


interface DatabaseItem {
    val id: Long
}

@Entity
data class ManyManyMatching(
    val leftID: Long,
    val rightID: Long,
    val type: Int,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

interface ReorderableDatabaseItem<T: ReorderableDatabaseItem<T>>: DatabaseItem {
    val position: Double
    fun withPosition(position: Double): T
}

@Dao
interface MatchingDao {
    @Upsert
    suspend fun upsert(value: ManyManyMatching): Long
    @Upsert
    suspend fun upsert(value: List<ManyManyMatching>)
    @Delete
    suspend fun delete(value: ManyManyMatching): Int

    @Query("SELECT rightID FROM ManyManyMatching WHERE leftID = :leftID AND type = :type")
    suspend fun getFromLeft(leftID: Long, type: Int): List<Long>
    @Query("SELECT leftID FROM ManyManyMatching WHERE rightID = :rightID AND type = :type")
    suspend fun getFromRight(rightID: Long, type: Int): List<Long>
    @Query("DELETE FROM ManyManyMatching WHERE leftID = :leftID AND type = :type")
    suspend fun deleteFromLeft(leftID: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE rightID = :rightID AND type = :type")
    suspend fun deleteFromRight(rightID: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE leftID = :left AND rightID = :right AND type = :type")
    suspend fun deleteMatch(left: Long, right: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE type = :type")
    suspend fun deleteByType(type: Int)

    @Query("DELETE FROM ManyManyMatching")
    suspend fun clear()
    @Query("SELECT * FROM ManyManyMatching")
    fun flow(): Flow<List<ManyManyMatching>>
}

val databases: MutableMap<KClass<*>, RoomDatabase> = mutableMapOf()

inline fun <reified T : RoomDatabase> closeCachedDatabase() {
    synchronized(databases) {
        val db = databases.remove(T::class)
        // Closing can race with in-flight queries; ignore the resulting failure
        // since we are discarding the instance anyway.
        try { db?.close() } catch (_: RuntimeException) {}
    }
}

/**
 * Implemented by a [RoomDatabase] companion object to declare the migrations
 * for that database in one place — alongside the schema definition itself
 * rather than scattered across every `buildDatabase()` call site.
 *
 * Example:
 * ```
 * abstract class NotesDatabase : RoomDatabase() {
 *     abstract fun notesDao(): NotesDao
 *     companion object : DatabaseMigrations {
 *         override val migrations = listOf(MIGRATION_1_2, MIGRATION_2_3)
 *     }
 * }
 * ```
 */
interface DatabaseMigrations {
    val migrations: List<Migration>
}

private var sqlCipherLoaded = false
fun loadSqlCipher() {
    if (sqlCipherLoaded) return
    try {
        System.loadLibrary("sqlcipher")
        sqlCipherLoaded = true
    } catch (e: UnsatisfiedLinkError) {
        e.printStackTrace()
    }
}

inline fun <reified T : RoomDatabase> Context.buildDatabase(
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T {
    loadSqlCipher()

    // Resolve migrations: explicit arg wins; otherwise read from the
    // database's companion object if it implements [DatabaseMigrations].
    val resolvedMigrations: List<Migration> = migrations ?: run {
        val companionField = try {
            T::class.java.getDeclaredField("Companion").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            null
        }
        val companionInstance = companionField?.get(null)
        (companionInstance as? DatabaseMigrations)?.migrations ?: emptyList()
    }

    val targetContext = if (useDeviceProtectedStorage) {
        val deviceContext = this.createDeviceProtectedStorageContext()
        val sharedPrefsName = "secure_prefs" // Matches DatabaseHelper.sharedPrefsName
        
        if (!deviceContext.getDatabasePath(dbName).exists() && this.getDatabasePath(dbName).exists()) {
            deviceContext.moveDatabaseFrom(this, dbName)
        }
        if (deviceContext.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE).all.isEmpty() && 
            this.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE).all.isNotEmpty()) {
            deviceContext.moveSharedPreferencesFrom(this, sharedPrefsName)
        }
        deviceContext
    } else {
        this
    }

    synchronized(databases) {
        if (databases[T::class] != null) return databases[T::class]!! as T

        var password = encryptionPassword
        if (password == null) {
            val helper = DatabaseHelper(targetContext)
            if (!helper.isKeyGenerated()) {
                helper.generateKey()
                val cipher = helper.getCipherForEncryption()
                password = helper.createAndStorePassphrase(cipher)
            } else {
                val cipher = helper.getCipherForDecryption()
                password = helper.decryptPassphrase(cipher)
            }
        }

        encryptExistingDatabase(targetContext, dbName, password)

        val builder = Room.databaseBuilder(
            targetContext,
            T::class.java,
            dbName
        ).addMigrations(*resolvedMigrations.toTypedArray())

        builder.openHelperFactory(SupportOpenHelperFactory(password.toByteArray(StandardCharsets.UTF_8)))

        val db = builder.build()
        databases[T::class] = db
        return db as T
    }
}

fun encryptExistingDatabase(context: Context, dbName: String, password: String) {
    loadSqlCipher()
    val dbFile = context.getDatabasePath(dbName)
    if (!dbFile.exists() || dbFile.length() < 16) return

    val isEncrypted = try {
        FileInputStream(dbFile).use { fis ->
            val header = ByteArray(16)
            if (fis.read(header) != 16) {
                true
            } else {
                !header.contentEquals("SQLite format 3\u0000".toByteArray(StandardCharsets.UTF_8))
            }
        }
    } catch (e: Exception) {
        true
    }

    if (isEncrypted) return

    // It's not encrypted. Let's encrypt it.
    val tempFile = context.getDatabasePath("${dbName}_temp")
    if (tempFile.exists()) tempFile.delete()
    tempFile.parentFile?.mkdirs()
    tempFile.createNewFile()

    try {
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            "",
            null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
            null
        )
        db.rawExecSQL("PRAGMA cipher_compatibility = 4")
        db.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '${password}'")
        db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        db.rawExecSQL("DETACH DATABASE encrypted")
        db.close()

        // Delete the original plain database and its journal/WAL files
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()

        tempFile.renameTo(dbFile)
    } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
        tempFile.delete()
    }
}

class DefaultConverters {
    @TypeConverter
    fun fromInstant(value: Instant) = value.epochSeconds
    @TypeConverter
    fun toInstant(value: Long) = Instant.fromEpochSeconds(value)
    @TypeConverter
    fun fromList(value: List<Long>?): String? {
        return value?.let { Json.encodeToString(it) }
    }
    @TypeConverter
    fun toList(value: String?): List<Long>? {
        return value?.let { Json.decodeFromString<List<Long>>(it) }
    }
    @TypeConverter
    fun fromListS(value: List<String>): String {
        return Json.encodeToString(value)
    }
    @TypeConverter
    fun toListS(value: String): List<String> {
        return Json.decodeFromString<List<String>>(value)
    }

    @TypeConverter
    fun fromDuration(value: Duration) = value.inWholeMilliseconds
    @TypeConverter
    fun toDuration(value: Long) = value.milliseconds

    @TypeConverter
    fun fromLocalTime(value: LocalTime) = value.toSecondOfDay()
    @TypeConverter
    fun toLocalTime(value: Int) = LocalTime.fromSecondOfDay(value)
}
