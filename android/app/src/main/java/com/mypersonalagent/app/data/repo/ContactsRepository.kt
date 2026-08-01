package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.remote.ContactDto
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only, online-only per PLAN.md Phase 3.2 - list + search only, no Room cache. */
@Singleton
class ContactsRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun list(query: String? = null): List<ContactDto> = api.listContacts(query)
}
