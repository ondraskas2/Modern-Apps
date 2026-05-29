package com.vayunmathur.youpipe.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(foreignKeys = [
    ForeignKey(entity = Subscription::class, parentColumns = ["id"], childColumns = ["subscriptionID"], onDelete = ForeignKey.CASCADE)
    ])
data class SubscriptionCategory(
    @ColumnInfo(index = true)
    val subscriptionID: Long,
    val category: String,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem

@Dao
interface SubscriptionCategoryDao {
    @Query("SELECT * FROM SubscriptionCategory")
    fun getAllFlow(): Flow<List<SubscriptionCategory>>

    @Query("SELECT * FROM SubscriptionCategory WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<SubscriptionCategory?>

    @Query("DELETE FROM SubscriptionCategory WHERE category = :categoryName")
    suspend fun deleteCategory(categoryName: String)

    @Upsert
    suspend fun upsertAll(items: List<SubscriptionCategory>)

    @Transaction
    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, map: List<Long>) {
        if(originalCategoryName != null) deleteCategory(originalCategoryName)
        upsertAll(map.mapIndexed { index, id -> SubscriptionCategory(id, categoryName) })
    }
}