package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.local.TodoEntity
import com.mypersonalagent.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone (2026-08-12 pivot): Room is the only source of truth, no server involved.
 * IDs and timestamps are generated on-device; there is nothing to push or pull.
 */
@Singleton
class TodoRepository @Inject constructor(
    private val dao: TodoDao,
    private val reminderScheduler: ReminderScheduler,
) {
    val todos: Flow<List<TodoEntity>> = dao.observeAll()

    /** No-op kept for ViewModel compatibility (pre-standalone this pulled from the server). */
    suspend fun refresh() {}

    suspend fun create(title: String, project: String, due: String?) {
        val now = OffsetDateTime.now().toString()
        dao.upsert(
            TodoEntity(
                id = UUID.randomUUID().toString(), title = title, project = project, due = due,
                status = "open", created = now, updated = now,
            )
        )
        reminderScheduler.requestImmediateCheck()
    }

    suspend fun complete(id: String) {
        val current = dao.getById(id) ?: return
        val now = OffsetDateTime.now().toString()
        dao.upsert(current.copy(status = "done", completed = now, updated = now))
    }

    suspend fun snooze(id: String, until: String) {
        val current = dao.getById(id) ?: return
        val now = OffsetDateTime.now().toString()
        dao.upsert(current.copy(status = "snoozed", snoozeUntil = until, updated = now, notifiedForDue = null))
        reminderScheduler.requestImmediateCheck()
    }

    suspend fun delete(id: String) {
        val current = dao.getById(id) ?: return
        val now = OffsetDateTime.now().toString()
        dao.upsert(current.copy(deleted = true, updated = now))
    }
}
