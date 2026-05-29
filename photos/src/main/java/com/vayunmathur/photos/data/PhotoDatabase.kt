package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Dao
interface PhotoDao {
    @Query("SELECT * FROM Photo")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<Photo>>

    @Query("SELECT * FROM Photo WHERE id = :id")
    fun getByIdFlow(id: Long): kotlinx.coroutines.flow.Flow<Photo?>

    @Query("SELECT * FROM Photo")
    suspend fun getAll(): List<Photo>

    @Query("SELECT * FROM Photo WHERE uri = :uri")
    suspend fun getByUri(uri: String): List<Photo>

    @androidx.room.Upsert
    suspend fun upsertAll(photos: List<Photo>)

    @androidx.room.Delete
    suspend fun delete(value: Photo): Int

    @Query("DELETE FROM Photo WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT Photo.* FROM Photo JOIN PhotoOCR ON Photo.id = PhotoOCR.rowid WHERE PhotoOCR MATCH :query AND Photo.isTrashed = 0")
    suspend fun searchPhotos(query: String): List<Photo>

    @androidx.room.Upsert
    suspend fun upsertOCR(ocr: PhotoOCR)

    @Query("DELETE FROM PhotoOCR WHERE rowid IN (:ids)")
    suspend fun deleteOCRByIds(ids: List<Long>)

    @Query("SELECT count(*) FROM PhotoOCR")
    fun getOCRCountFlow(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT count(*) FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    fun getOCRTargetCountFlow(): kotlinx.coroutines.flow.Flow<Int>
}

@Database(entities = [Photo::class, PhotoOCR::class], version = 5, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_Photo_date` ON `Photo` (`date`)")
}

val MIGRATION_2_3 = Migration(2, 3) {
    it.execSQL("ALTER TABLE Photo ADD COLUMN dateModified INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_3_4 = Migration(3, 4) {
    it.execSQL("ALTER TABLE Photo ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_4_5 = Migration(4, 5) {
    it.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `PhotoOCR` USING FTS4(`ocrText` TEXT)")
}
