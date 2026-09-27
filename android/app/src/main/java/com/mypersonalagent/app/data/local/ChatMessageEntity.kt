package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages", indices = [Index("threadId"), Index("created")])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val role: String,
    val content: String,
    val speakerId: String? = null,
    val speakerName: String? = null,
    val kind: String = "text",
    val created: String,
)
