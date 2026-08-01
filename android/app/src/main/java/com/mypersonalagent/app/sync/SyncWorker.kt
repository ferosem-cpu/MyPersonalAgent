package com.mypersonalagent.app.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mypersonalagent.app.data.local.EntryDao
import com.mypersonalagent.app.data.local.EntryEntity
import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.local.TodoEntity
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.remote.EntryDto
import com.mypersonalagent.app.data.remote.SyncRequest
import com.mypersonalagent.app.data.remote.TodoDto
import com.mypersonalagent.app.data.repo.SettingsRepository
import com.mypersonalagent.app.notifications.ReminderNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val api: ApiService,
    private val todoDao: TodoDao,
    private val entryDao: EntryDao,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = try {
        val lastSync = settings.lastSync.firstOrNull()

        val pendingTodos = todoDao.pendingSync().map { json.encodeToJsonElement(it.toDto()) }
        val pendingEntries = entryDao.pendingSync().map { json.encodeToJsonElement(it.toDto()) }
        val changes = buildMap {
            if (pendingTodos.isNotEmpty()) put("todos", pendingTodos)
            if (pendingEntries.isNotEmpty()) put("entries", pendingEntries)
        }

        val resp = api.sync(SyncRequest(lastSync = lastSync, changes = changes))

        applyServerTodos(resp.changes["todos"].orEmpty())
        applyServerEntries(resp.changes["entries"].orEmpty())

        todoDao.clearPendingSync(resp.applied["todos"].orEmpty())
        entryDao.clearPendingSync(resp.applied["entries"].orEmpty())

        // Rejected pushes: the server's copy won (it was newer) - pull it in
        // and drop our stale local pendingSync flag so we stop re-pushing it.
        applyRejected("todos", resp.rejected["todos"].orEmpty())
        applyRejected("entries", resp.rejected["entries"].orEmpty())

        settings.setLastSync(resp.serverTime)
        ReminderNotifier.checkAndNotify(applicationContext, todoDao)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    private suspend fun applyServerTodos(items: List<JsonElement>) {
        for (el in items) {
            val dto = json.decodeFromJsonElement<TodoDto>(el)
            val id = dto.id ?: continue
            val local = todoDao.getById(id)
            // If we have a not-yet-pushed local edit newer than this pull, keep it - it wins next push.
            if (local != null && local.pendingSync && local.updated > (dto.updated ?: "")) continue
            // Preserve the local "already notified" marker when the due date hasn't actually
            // changed, so a routine 15-min re-sync doesn't reset it and re-fire the reminder.
            val notifiedForDue = local?.notifiedForDue.takeIf { local?.due == dto.due }
            todoDao.upsert(TodoEntity.fromDto(dto, pendingSync = false, notifiedForDue = notifiedForDue))
        }
    }

    private suspend fun applyServerEntries(items: List<JsonElement>) {
        for (el in items) {
            val dto = json.decodeFromJsonElement<EntryDto>(el)
            val id = dto.id ?: continue
            val local = entryDao.getById(id)
            if (local != null && local.pendingSync && local.updated > (dto.updated ?: "")) continue
            entryDao.upsert(EntryEntity.fromDto(dto, pendingSync = false))
        }
    }

    private suspend fun applyRejected(collection: String, rejected: List<JsonElement>) {
        for (el in rejected) {
            val obj = el as? kotlinx.serialization.json.JsonObject ?: continue
            val serverCopy = obj["server_copy"] ?: continue
            when (collection) {
                "todos" -> {
                    val dto = json.decodeFromJsonElement<TodoDto>(serverCopy)
                    dto.id?.let { todoDao.upsert(TodoEntity.fromDto(dto, pendingSync = false)) }
                }
                "entries" -> {
                    val dto = json.decodeFromJsonElement<EntryDto>(serverCopy)
                    dto.id?.let { entryDao.upsert(EntryEntity.fromDto(dto, pendingSync = false)) }
                }
            }
        }
    }
}
