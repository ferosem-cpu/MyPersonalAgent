package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mypersonalagent.app.data.remote.EntryDto

@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey val id: String,
    val ts: String,
    val title: String,
    val desc: String = "",
    val project: String = "",
    val minutes: Int = 0,
    val updated: String,
    val deleted: Boolean = false,
    val pendingSync: Boolean = false,
    val locallyDeleted: Boolean = false,
) {
    fun toDto() = EntryDto(
        id = id, ts = ts, title = title, desc = desc, project = project,
        minutes = minutes, updated = updated, deleted = deleted || locallyDeleted,
    )

    companion object {
        fun fromDto(dto: EntryDto, pendingSync: Boolean) = EntryEntity(
            id = dto.id ?: error("server entry missing id"),
            ts = dto.ts ?: "", title = dto.title, desc = dto.desc, project = dto.project,
            minutes = dto.minutes, updated = dto.updated ?: "", deleted = dto.deleted,
            pendingSync = pendingSync,
        )
    }
}
