package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.mypersonalagent.app.data.remote.NoteDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val text: String,
    /** Stored as a JSON array string, e.g. ["work","idea"]. */
    val tagsJson: String = "[]",
    val created: String? = null,
    val updated: String,
    val deleted: Boolean = false,
) {
    @Ignore
    fun tagsList(): List<String> =
        runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList())

    fun toDto() = NoteDto(
        id = id, text = text, tags = tagsList(), created = created, updated = updated, deleted = deleted,
    )

    companion object {
        fun of(id: String, text: String, tags: List<String>, created: String?, updated: String, deleted: Boolean = false) =
            NoteEntity(
                id = id, text = text, tagsJson = Json.encodeToString(tags),
                created = created, updated = updated, deleted = deleted,
            )
    }
}
