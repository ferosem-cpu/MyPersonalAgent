package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.EntryDao
import com.mypersonalagent.app.data.local.EntryEntity
import kotlinx.coroutines.flow.Flow
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Standalone (2026-08-12 pivot): Room only, no server. */
@Singleton
class EntryRepository @Inject constructor(
    private val dao: EntryDao,
) {
    val entries: Flow<List<EntryEntity>> = dao.observeAll()

    /** No-op kept for ViewModel compatibility (pre-standalone this pulled from the server). */
    suspend fun refresh() {}

    suspend fun logWork(title: String, desc: String, project: String, minutes: Int) {
        val now = OffsetDateTime.now().toString()
        dao.upsert(
            EntryEntity(
                id = UUID.randomUUID().toString(), ts = now, title = title, desc = desc,
                project = project, minutes = minutes, updated = now,
            )
        )
    }
}
