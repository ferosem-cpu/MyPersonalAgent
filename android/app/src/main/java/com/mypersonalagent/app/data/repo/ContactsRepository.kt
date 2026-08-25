package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.ContactDao
import com.mypersonalagent.app.data.local.ContactEntity
import com.mypersonalagent.app.data.remote.ContactDto
import kotlinx.coroutines.flow.first
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsRepository @Inject constructor(
    private val dao: ContactDao,
) {
    suspend fun list(query: String? = null): List<ContactDto> {
        val all = dao.observeAll().first().map { it.toDto() }
        if (query.isNullOrBlank()) return all
        return all.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.phoneNumber.orEmpty().contains(query) ||
                it.email.orEmpty().contains(query, ignoreCase = true)
        }
    }

    suspend fun save(name: String, phone: String?, email: String?): ContactDto {
        val now = OffsetDateTime.now().toString()
        val entity = ContactEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            phoneNumber = phone?.trim()?.ifBlank { null },
            email = email?.trim()?.ifBlank { null },
            created = now,
            updated = now,
        )
        dao.upsert(entity)
        return entity.toDto()
    }

    suspend fun delete(id: String) {
        dao.softDelete(id, OffsetDateTime.now().toString())
    }

    suspend fun exportVcf(): String {
        return list().joinToString("\n") { c ->
            buildString {
                appendLine("BEGIN:VCARD")
                appendLine("VERSION:3.0")
                appendLine("FN:${c.name}")
                c.phoneNumber?.takeIf { it.isNotBlank() }?.let { appendLine("TEL:$it") }
                c.email?.takeIf { it.isNotBlank() }?.let { appendLine("EMAIL:$it") }
                appendLine("END:VCARD")
            }
        }
    }
}
