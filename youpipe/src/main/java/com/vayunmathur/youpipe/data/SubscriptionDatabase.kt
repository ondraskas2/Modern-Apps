package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `DownloadedVideo` (
                `id` INTEGER NOT NULL, 
                `name` TEXT NOT NULL, 
                `videoID` INTEGER NOT NULL, 
                `duration` INTEGER NOT NULL, 
                `views` INTEGER NOT NULL, 
                `uploadDate` INTEGER NOT NULL, 
                `thumbnailURL` TEXT NOT NULL, 
                `author` TEXT NOT NULL, 
                `filePath` TEXT NOT NULL, 
                `audioPath` TEXT, 
                `timestamp` INTEGER NOT NULL, 
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
    }
}

@Dao
interface HistoryVideoDao : TrueDao<HistoryVideo> {
    @Query("SELECT * FROM HistoryVideo")
    fun getAllFlow(): Flow<List<HistoryVideo>>

    @Query("SELECT * FROM HistoryVideo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<HistoryVideo?>
}

@Dao
interface SubscriptionDao : TrueDao<Subscription> {
    @Query("SELECT * FROM Subscription")
    fun getAllFlow(): Flow<List<Subscription>>

    @Query("SELECT * FROM Subscription WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Subscription?>

    @Query("SELECT * FROM Subscription")
    suspend fun getAll(): List<Subscription>

    @Query("DELETE FROM Subscription")
    suspend fun clearAll()
}

@Dao
interface SubscriptionVideoDao : TrueDao<SubscriptionVideo> {
    @Query("SELECT * FROM SubscriptionVideo")
    fun getAllFlow(): Flow<List<SubscriptionVideo>>

    @Query("SELECT * FROM SubscriptionVideo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<SubscriptionVideo?>
}

@TypeConverters(DefaultConverters::class)
@Database(entities = [Subscription::class, SubscriptionVideo::class, HistoryVideo::class, SubscriptionCategory::class, DownloadedVideo::class], version = 2)
abstract class SubscriptionDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun subscriptionVideoDao(): SubscriptionVideoDao
    abstract fun historyVideoDao(): HistoryVideoDao
    abstract fun subscriptionCategoryDao(): SubscriptionCategoryDao
    abstract fun downloadedVideoDao(): DownloadedVideoDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2)
    }
}
