package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Entity
data class CachedRelatedVideo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceVideoID: Long,
    @Embedded val videoItem: VideoInfo,
    val cachedAt: Instant
)

@Dao
interface CachedRelatedVideoDao {
    @Upsert
    suspend fun upsertAll(values: List<CachedRelatedVideo>)

    @Query("SELECT * FROM CachedRelatedVideo")
    fun getAllFlow(): Flow<List<CachedRelatedVideo>>

    @Query("SELECT * FROM CachedRelatedVideo")
    suspend fun getAll(): List<CachedRelatedVideo>

    @Query("DELETE FROM CachedRelatedVideo WHERE cachedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Instant)
}
