package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.remote.NoteDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online-only, unlike Todos/Entries - per PLAN.md Phase 3.2 this hits the API directly
 * rather than going through the Room offline-sync pipeline built in Phase 2.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun list(): List<NoteDto> = api.listNotes()

    suspend fun recall(query: String): List<NoteDto> = api.recallNotes(query)

    suspend fun remember(text: String): NoteDto = api.createNote(NoteDto(text = text))
}
