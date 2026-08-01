package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.EntryDao
import com.mypersonalagent.app.data.local.EntryEntity
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EntryRepository @Inject constructor(
    private val api: ApiService,
    private val dao: EntryDao,
    private val syncScheduler: SyncScheduler,
) {
    val entries: Flow<List<EntryEntity>> = dao.observeAll()

    suspend fun refresh() {
        val remote = api.listEntries()
        dao.upsertAll(remote.map { EntryEntity.fromDto(it, pendingSync = false) })
    }

    suspend fun logWork(title: String, desc: String, project: String, minutes: Int) {
        val now = OffsetDateTime.now().toString()
        dao.upsert(
            EntryEntity(
                id = UUID.randomUUID().toString(), ts = now, title = title, desc = desc,
                project = project, minutes = minutes, updated = now, pendingSync = true,
            )
        )
        syncScheduler.requestExpedited()
    }
}
