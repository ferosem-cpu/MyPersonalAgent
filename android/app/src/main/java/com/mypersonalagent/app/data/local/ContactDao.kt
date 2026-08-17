package com.mypersonalagent.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE deleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContactEntity?

    @Query("UPDATE contacts SET deleted = 1, updated = :updated WHERE id = :id")
    suspend fun softDelete(id: String, updated: String)
}
