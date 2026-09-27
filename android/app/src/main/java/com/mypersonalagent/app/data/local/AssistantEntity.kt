package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assistants")
data class AssistantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val title: String,
    val instructions: String,
    val colorHex: String,
    val shape: String = "round",
    val emoji: String = "",
    val isGroup: Boolean = false,
    val memberIdsJson: String = "[]",
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val created: String,
    val updated: String,
    val lastActive: String,
)
