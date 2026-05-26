package com.vayunmathur.email.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailMessage

@Database(entities = [EmailFolder::class, EmailMessage::class], version = 1)
abstract class EmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao

    companion object {
        @Volatile
        private var instance: EmailDatabase? = null

        fun getInstance(context: Context): EmailDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmailDatabase::class.java,
                    "email-db"
                ).build().also { instance = it }
            }
        }
    }
}
