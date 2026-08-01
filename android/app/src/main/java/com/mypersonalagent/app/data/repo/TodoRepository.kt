package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.local.TodoEntity
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TodoRepository @Inject constructor(
    private val api: ApiService,
    private val dao: TodoDao,
    private val syncScheduler: SyncScheduler,
) {
    val todos: Flow<List<TodoEntity>> = dao.observeAll() // UI always reads Room (single source of truth)

    suspend fun refresh() { // one-time full pull, used on first load; steady state is via SyncWorker
        val remote = api.listTodos("all")
        dao.upsertAll(remote.map { TodoEntity.fromDto(it, pendingSync = false) })
    }

    /** Write-locally-first (Phase 2): the UI update and any offline usage never
     * wait on the network - a background sync (triggered here, and periodically) pushes it. */
    suspend fun create(title: String, project: String, due: String?) {
        val now = OffsetDateTime.now().toString()
        dao.upsert(
            TodoEntity(
                id = UUID.randomUUID().toString(), title = title, project = project, due = due,
                status = "open", created = now, updated = now, pendingSync = true,
            )
        )
        syncScheduler.requestExpedited()
    }

    suspend fun complete(id: String) {
        val current = dao.getById(id) ?: return
        val now = OffsetDateTime.now().toString()
        dao.upsert(current.copy(status = "done", completed = now, updated = now, pendingSync = true))
        syncScheduler.requestExpedited()
    }

    suspend fun snooze(id: String, until: String) {
        val current = dao.getById(id) ?: return
        val now = OffsetDateTime.now().toString()
        dao.upsert(current.copy(status = "snoozed", snoozeUntil = until, updated = now, pendingSync = true))
        syncScheduler.requestExpedited()
    }

    suspend fun delete(id: String) {
        val current = dao.getById(id) ?: return
        val now = OffsetDateTime.now().toString()
        dao.upsert(current.copy(locallyDeleted = true, updated = now, pendingSync = true))
        syncScheduler.requestExpedited()
    }
}
