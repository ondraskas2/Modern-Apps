package com.vayunmathur.notes.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM Note")
    fun getAllFlow(): Flow<List<Note>>

    @Query("SELECT * FROM Note")
    suspend fun getAll(): List<Note>

    @Query("SELECT * FROM Note WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Note?>

    @Upsert
    suspend fun upsert(value: Note): Long

    @Delete
    suspend fun delete(value: Note): Int

    @Upsert
    suspend fun upsertAll(t: List<Note>)
}

@Database(entities = [Note::class], version = 1)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
