package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A per-keyword recommendation preference keyed by the lowercased token
 * ([keyword]). When [muted], candidates whose title tokens include this keyword
 * are hard-filtered from the feed.
 */
@Entity
data class KeywordPreference(
    @PrimaryKey val keyword: String,
    val muted: Boolean = true,
)

@Dao
interface KeywordPreferenceDao {
    @Query("SELECT * FROM KeywordPreference")
    suspend fun getAll(): List<KeywordPreference>

    @Query("SELECT * FROM KeywordPreference")
    fun getAllFlow(): Flow<List<KeywordPreference>>

    @Upsert
    suspend fun upsert(value: KeywordPreference)

    @Query("DELETE FROM KeywordPreference WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM KeywordPreference")
    suspend fun clearAll()
}
