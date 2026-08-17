package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.ContactDao
import com.mypersonalagent.app.data.remote.ContactDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Standalone (2026-08-12 pivot): Room only, no server. Public shape (List<ContactDto>)
 * kept as-is so ContactsViewModel/ContactsScreen don't need to change yet. */
@Singleton
class ContactsRepository @Inject constructor(
    private val dao: ContactDao,
) {
    suspend fun list(query: String? = null): List<ContactDto> {
        val all = dao.observeAll().first().map { it.toDto() }
        if (query.isNullOrBlank()) return all
        return all.filter { it.name.contains(query, ignoreCase = true) }
    }
}
