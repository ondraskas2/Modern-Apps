package com.vayunmathur.email.data

import androidx.room.*
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {
    @Query("SELECT * FROM EmailFolder")
    fun getFoldersFlow(): Flow<List<EmailFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<EmailFolder>)

    @Query("SELECT * FROM EmailMessage WHERE folderName = :folderName ORDER BY id DESC")
    fun getMessagesFlow(folderName: String): Flow<List<EmailMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<EmailMessage>)

    @Query("SELECT * FROM EmailMessage WHERE id = :uid AND folderName = :folderName")
    suspend fun getMessage(folderName: String, uid: Long): EmailMessage?

    @Query("DELETE FROM EmailFolder")
    suspend fun clearFolders()

    @Query("DELETE FROM EmailMessage")
    suspend fun clearMessages()

    @Query("SELECT * FROM EmailMessage WHERE folderName = :folderName AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY id DESC")
    fun searchMessagesFlow(folderName: String, query: String): Flow<List<EmailMessage>>
}
