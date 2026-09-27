package com.mypersonalagent.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AssistantDao {
    @Query("SELECT * FROM assistants WHERE hidden = 0 ORDER BY pinned DESC, lastActive DESC")
    fun observeAll(): Flow<List<AssistantEntity>>

    @Query("SELECT * FROM assistants WHERE hidden = 0 ORDER BY pinned DESC, lastActive DESC")
    suspend fun list(): List<AssistantEntity>

    @Query("SELECT * FROM assistants WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AssistantEntity?

    @Query("SELECT COUNT(*) FROM assistants")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AssistantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AssistantEntity>)

    @Update
    suspend fun update(entity: AssistantEntity)

    @Query("UPDATE assistants SET lastActive = :iso, updated = :iso WHERE id = :id")
    suspend fun touch(id: String, iso: String)

    @Query("DELETE FROM assistants WHERE id = :id")
    suspend fun delete(id: String)
}
