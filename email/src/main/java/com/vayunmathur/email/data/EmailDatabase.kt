package com.vayunmathur.email.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.email.EmailAccount
import com.vayunmathur.email.Attachment
import com.vayunmathur.email.OutboxEntry

@Database(
    entities = [
        EmailFolder::class,
        EmailMessage::class,
        EmailAccount::class,
        Attachment::class,
        OutboxEntry::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class EmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao

    companion object {
        @Volatile
        private var instance: EmailDatabase? = null

        /**
         * v4 → v5: Adds the OutboxEntry table. Non-destructive — keeps accounts,
         * folders, messages, and attachments intact across the upgrade.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `OutboxEntry` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `accountEmail` TEXT NOT NULL,
                        `to` TEXT NOT NULL,
                        `cc` TEXT,
                        `subject` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `attachmentLocalPaths` TEXT NOT NULL DEFAULT '[]',
                        `inReplyTo` TEXT,
                        `references` TEXT,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `lastError` TEXT,
                        `attemptCount` INTEGER NOT NULL DEFAULT 0,
                        `lastAttemptAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v5 → v6: Add `dateMillis` column for proper chronological ordering of
         * messages (the existing `date` is a `Date.toString()` string that
         * sorts lexically — wrong, especially in the unified inbox). Existing
         * rows get 0; backfilled at app start from the parsed `date` string.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE EmailMessage ADD COLUMN dateMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 → v7: Extend [EmailAccount] with per-account IMAP/SMTP server
         * config, a provider identifier, and an auth-type discriminator so we
         * can support providers beyond Gmail (Outlook, Yahoo, iCloud, Fastmail,
         * and arbitrary IMAP/SMTP servers). All defaults match the previous
         * hard-coded Gmail values so existing rows continue to sync.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN provider TEXT NOT NULL DEFAULT 'gmail'")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN imapHost TEXT NOT NULL DEFAULT 'imap.gmail.com'")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN imapPort INTEGER NOT NULL DEFAULT 993")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN imapUseSsl INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN smtpHost TEXT NOT NULL DEFAULT 'smtp.gmail.com'")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN smtpPort INTEGER NOT NULL DEFAULT 465")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN smtpUseSsl INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN authType TEXT NOT NULL DEFAULT 'oauth2'")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN passwordEncrypted BLOB")
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN passwordIv BLOB")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE EmailAccount ADD COLUMN username TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE EmailAccount SET username = email")
            }
        }

        fun getInstance(context: Context): EmailDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmailDatabase::class.java,
                    "email-db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build().also { instance = it }
            }
        }
    }
}
