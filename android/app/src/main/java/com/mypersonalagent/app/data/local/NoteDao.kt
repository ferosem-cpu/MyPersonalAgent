package com.mypersonalagent.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY updated DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND text LIKE '%' || :q || '%' ORDER BY updated DESC")
    suspend fun search(q: String): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteEntity?

    @Query("UPDATE notes SET deleted = 1, updated = :updated WHERE id = :id")
    suspend fun softDelete(id: String, updated: String)
}
