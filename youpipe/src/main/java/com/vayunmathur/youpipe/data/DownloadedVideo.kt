package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Entity
data class DownloadedVideo(
    @PrimaryKey override val id: Long, // video id
    @Embedded val videoItem: VideoInfo,
    val filePath: String,
    val audioPath: String? = null,
    val timestamp: Instant
): DatabaseItem

@Dao
interface DownloadedVideoDao : TrueDao<DownloadedVideo> {
    @Query("SELECT * FROM DownloadedVideo")
    fun getAllFlow(): Flow<List<DownloadedVideo>>

    @Query("SELECT * FROM DownloadedVideo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<DownloadedVideo?>
}
