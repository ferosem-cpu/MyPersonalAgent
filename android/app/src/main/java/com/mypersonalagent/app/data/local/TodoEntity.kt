package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mypersonalagent.app.data.remote.TodoDto

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,
    val title: String,
    val project: String = "",
    val due: String? = null,
    val recurrence: String? = null,
    val remindBeforeMin: Int = 30,
    val status: String = "open",
    val snoozeUntil: String? = null,
    val created: String? = null,
    val completed: String? = null,
    val lastReminded: String? = null,
    val escalationStep: Int = 0,
    val updated: String,
    val deleted: Boolean = false,
    val pendingSync: Boolean = false,
    val locallyDeleted: Boolean = false,
    /** Local-only, never synced: the `due` value we last posted a reminder notification for. */
    val notifiedForDue: String? = null,
) {
    fun toDto() = TodoDto(
        id = id, title = title, project = project, due = due, recurrence = recurrence,
        remindBeforeMin = remindBeforeMin, status = status, snoozeUntil = snoozeUntil,
        created = created, completed = completed, lastReminded = lastReminded,
        escalationStep = escalationStep, updated = updated, deleted = deleted || locallyDeleted,
    )

    companion object {
        fun fromDto(dto: TodoDto, pendingSync: Boolean, notifiedForDue: String? = null) = TodoEntity(
            id = dto.id ?: error("server todo missing id"),
            title = dto.title, project = dto.project, due = dto.due, recurrence = dto.recurrence,
            remindBeforeMin = dto.remindBeforeMin, status = dto.status, snoozeUntil = dto.snoozeUntil,
            created = dto.created, completed = dto.completed, lastReminded = dto.lastReminded,
            escalationStep = dto.escalationStep, updated = dto.updated ?: "", deleted = dto.deleted,
            pendingSync = pendingSync, notifiedForDue = notifiedForDue,
        )
    }
}
