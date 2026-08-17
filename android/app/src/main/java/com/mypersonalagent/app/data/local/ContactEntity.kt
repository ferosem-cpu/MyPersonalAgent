package com.mypersonalagent.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mypersonalagent.app.data.remote.ContactDto
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
    val telegramUserId: String? = null,
    val whatsappNumber: String? = null,
    val emailAccountsNote: String? = null,
    val created: String? = null,
    val updated: String,
    val deleted: Boolean = false,
) {
    fun toDto() = ContactDto(
        id = id, name = name, firstName = firstName, lastName = lastName,
        phoneNumber = phoneNumber, email = email, telegramUserId = telegramUserId,
        whatsappNumber = whatsappNumber, emailAccountsNote = emailAccountsNote,
        created = created, updated = updated, deleted = deleted,
    )

    companion object {
        fun fromDto(dto: ContactDto, updated: String) = ContactEntity(
            id = dto.id ?: error("contact missing id"),
            name = dto.name, firstName = dto.firstName, lastName = dto.lastName,
            phoneNumber = dto.phoneNumber, email = dto.email, telegramUserId = dto.telegramUserId,
            whatsappNumber = dto.whatsappNumber, emailAccountsNote = dto.emailAccountsNote,
            created = dto.created, updated = updated, deleted = dto.deleted,
        )
    }
}
