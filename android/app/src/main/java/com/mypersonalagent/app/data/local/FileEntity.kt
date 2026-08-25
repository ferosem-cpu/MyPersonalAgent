package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val category: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long = 0,
    val source: String = "share",
    val created: String,
)
