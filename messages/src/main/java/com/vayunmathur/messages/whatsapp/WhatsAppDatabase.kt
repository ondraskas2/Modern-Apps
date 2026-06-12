package com.vayunmathur.messages.whatsapp

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Room database for WhatsApp-specific data.
 * Stores device info, session keys, conversations, media requests, and avatar cache.
 * Aligned with Go wadb.Database which has: Conversation, Message, PollOption,
 * MediaRequest, HSNotif, AvatarCache queries.
 */
@Database(
    entities = [
        WhatsAppDevice::class,
        WhatsAppSession::class,
        WhatsAppConversation::class,
        WhatsAppMediaRequest::class,
        WhatsAppAvatarCache::class,
        WhatsAppPollOption::class,
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(WhatsAppTypeConverters::class)
abstract class WhatsAppDatabase : RoomDatabase() {
    abstract fun deviceDao(): WhatsAppDeviceDao
    abstract fun sessionDao(): WhatsAppSessionDao
    abstract fun conversationDao(): WhatsAppConversationDao
    abstract fun mediaRequestDao(): WhatsAppMediaRequestDao
    abstract fun avatarCacheDao(): WhatsAppAvatarCacheDao
    abstract fun pollOptionDao(): WhatsAppPollOptionDao

    companion object {
        @Volatile
        private var INSTANCE: WhatsAppDatabase? = null

        fun getDatabase(context: Context): WhatsAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WhatsAppDatabase::class.java,
                    "whatsapp_database"
                ).fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class WhatsAppTypeConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }
}

/**
 * History sync conversation, matching Go wadb.Conversation.
 */
@Entity(tableName = "whatsapp_history_sync_conversation")
data class WhatsAppConversation(
    @PrimaryKey
    val chatJid: String,
    val userLoginId: String = "",
    val lastMessageTimestamp: Long = 0L,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muteEndTime: Long = 0L,
    val endOfHistoryTransferType: Int = 0,
    val ephemeralExpiration: Long = 0L,
    val ephemeralSettingTimestamp: Long = 0L,
    val markedAsUnread: Boolean = false,
    val unreadCount: Int = 0,
)

@Dao
interface WhatsAppConversationDao {
    @Query("SELECT * FROM whatsapp_history_sync_conversation WHERE chatJid = :chatJid")
    suspend fun getConversation(chatJid: String): WhatsAppConversation?

    @Query("SELECT * FROM whatsapp_history_sync_conversation ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<WhatsAppConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: WhatsAppConversation)

    @Query("DELETE FROM whatsapp_history_sync_conversation WHERE chatJid = :chatJid")
    suspend fun delete(chatJid: String)

    @Query("DELETE FROM whatsapp_history_sync_conversation")
    suspend fun deleteAll()

    @Query("UPDATE whatsapp_history_sync_conversation SET muteEndTime = :muteEndTime WHERE chatJid = :chatJid")
    suspend fun updateMuteEndTime(chatJid: String, muteEndTime: Long)

    @Query("UPDATE whatsapp_history_sync_conversation SET pinned = :pinned WHERE chatJid = :chatJid")
    suspend fun updatePinned(chatJid: String, pinned: Boolean)

    @Query("UPDATE whatsapp_history_sync_conversation SET archived = :archived WHERE chatJid = :chatJid")
    suspend fun updateArchived(chatJid: String, archived: Boolean)

    @Query("UPDATE whatsapp_history_sync_conversation SET markedAsUnread = :unread WHERE chatJid = :chatJid")
    suspend fun updateMarkedAsUnread(chatJid: String, unread: Boolean)
}

/**
 * Media backfill request, matching Go wadb.MediaRequest.
 */
@Entity(tableName = "whatsapp_media_backfill_request")
data class WhatsAppMediaRequest(
    @PrimaryKey
    val messageId: String,
    val userLoginId: String = "",
    val portalId: String = "",
    val portalReceiver: String = "",
    val mediaKey: ByteArray = ByteArray(0),
    val status: Int = 0, // 0=not_requested, 1=requested, 2=failed, 3=skipped
    val error: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WhatsAppMediaRequest
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}

@Dao
interface WhatsAppMediaRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: WhatsAppMediaRequest)

    @Query("DELETE FROM whatsapp_media_backfill_request WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("SELECT * FROM whatsapp_media_backfill_request WHERE status = 0")
    suspend fun getUnrequested(): List<WhatsAppMediaRequest>
}

/**
 * Avatar cache entry, matching Go wadb.AvatarCacheEntry.
 */
@Entity(tableName = "whatsapp_avatar_cache", primaryKeys = ["entityJid", "avatarId"])
data class WhatsAppAvatarCache(
    val entityJid: String,
    val avatarId: String,
    val directPath: String = "",
    val expiry: Long = 0L,
    val gone: Boolean = false,
)

@Dao
interface WhatsAppAvatarCacheDao {
    @Query("SELECT * FROM whatsapp_avatar_cache WHERE entityJid = :entityJid AND avatarId = :avatarId")
    suspend fun get(entityJid: String, avatarId: String): WhatsAppAvatarCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: WhatsAppAvatarCache)
}

/**
 * Poll option hash mapping, matching Go wadb.PollOption.
 * Maps SHA256 option hashes to option string IDs for poll vote resolution.
 */
@Entity(tableName = "whatsapp_poll_option", primaryKeys = ["msgId", "optionHash"])
data class WhatsAppPollOption(
    val msgId: String,
    val optionHash: String,
    val optionName: String,
)

@Dao
interface WhatsAppPollOptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(option: WhatsAppPollOption)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(options: List<WhatsAppPollOption>)

    @Query("SELECT * FROM whatsapp_poll_option WHERE msgId = :msgId")
    suspend fun getByMessageId(msgId: String): List<WhatsAppPollOption>

    @Query("SELECT * FROM whatsapp_poll_option WHERE msgId = :msgId AND optionHash = :hash")
    suspend fun getByHash(msgId: String, hash: String): WhatsAppPollOption?

    @Query("DELETE FROM whatsapp_poll_option WHERE msgId = :msgId")
    suspend fun deleteByMessageId(msgId: String)
}
