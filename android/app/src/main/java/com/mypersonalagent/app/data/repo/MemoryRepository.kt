package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.NoteDao
import com.mypersonalagent.app.data.local.NoteEntity
import com.mypersonalagent.app.data.remote.NoteDto
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Standalone (2026-08-12 pivot): Room only, no server. Public shape (List<NoteDto>) kept
 * as-is so MemoryViewModel/MemoryScreen don't need to change yet. */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: NoteDao,
) {
    /** One-shot read of all notes (search with an empty query matches everything). */
    suspend fun list(): List<NoteDto> = dao.search("").map { it.toDto() }

    suspend fun recall(query: String): List<NoteDto> = dao.search(query).map { it.toDto() }

    suspend fun remember(text: String): NoteDto {
        val now = OffsetDateTime.now().toString()
        val entity = NoteEntity.of(
            id = UUID.randomUUID().toString(), text = text, tags = emptyList(),
            created = now, updated = now,
        )
        dao.upsert(entity)
        return entity.toDto()
    }
}
