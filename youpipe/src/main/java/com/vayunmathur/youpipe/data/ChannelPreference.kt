package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A per-channel recommendation preference keyed by the lowercased author name
 * ([channelKey]). [multiplier] scales the channel's candidate scores
 * (>1 boosts, <1 demotes, 1.0 = neutral). [blocked] hard-filters the channel;
 * [pinned] forces its items to the top of the feed.
 */
@Entity
data class ChannelPreference(
    @PrimaryKey val channelKey: String,
    val multiplier: Double = 1.0,
    val blocked: Boolean = false,
    val pinned: Boolean = false,
)

@Dao
interface ChannelPreferenceDao {
    @Query("SELECT * FROM ChannelPreference")
    suspend fun getAll(): List<ChannelPreference>

    @Query("SELECT * FROM ChannelPreference")
    fun getAllFlow(): Flow<List<ChannelPreference>>

    @Query("SELECT * FROM ChannelPreference WHERE channelKey = :channelKey")
    suspend fun get(channelKey: String): ChannelPreference?

    @Upsert
    suspend fun upsert(value: ChannelPreference)

    @Query("DELETE FROM ChannelPreference WHERE channelKey = :channelKey")
    suspend fun delete(channelKey: String)

    @Query("DELETE FROM ChannelPreference")
    suspend fun clearAll()
}
