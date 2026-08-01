package com.mypersonalagent.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE deleted = 0 AND locallyDeleted = 0 ORDER BY COALESCE(snoozeUntil, due) ASC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE pendingSync = 1")
    suspend fun pendingSync(): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: TodoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TodoEntity>)

    @Query("UPDATE todos SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingSync(ids: List<String>)

    @Query("SELECT * FROM todos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TodoEntity?

    @Query("SELECT * FROM todos WHERE deleted = 0 AND locallyDeleted = 0 AND status = 'open'")
    suspend fun openTodos(): List<TodoEntity>

    @Query("UPDATE todos SET notifiedForDue = :due WHERE id = :id")
    suspend fun setNotifiedForDue(id: String, due: String?)
}
