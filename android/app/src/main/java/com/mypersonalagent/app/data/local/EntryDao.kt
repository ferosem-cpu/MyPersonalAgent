package com.mypersonalagent.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE deleted = 0 AND locallyDeleted = 0 ORDER BY ts DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE pendingSync = 1")
    suspend fun pendingSync(): List<EntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EntryEntity>)

    @Query("UPDATE entries SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingSync(ids: List<String>)

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EntryEntity?
}
