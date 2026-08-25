package com.mypersonalagent.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.remote.ContactDto
import com.mypersonalagent.app.data.repo.ContactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _contacts = MutableStateFlow<List<ContactDto>>(emptyList())
    val contacts: StateFlow<List<ContactDto>> = _contacts.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _vcf = MutableStateFlow<String?>(null)
    val vcf: StateFlow<String?> = _vcf.asStateFlow()

    init {
        refresh()
    }

    fun refresh(query: String? = null) {
        viewModelScope.launch {
            _loading.value = true
            runCatching { repository.list(query?.takeIf { it.isNotBlank() }) }
                .onSuccess { _contacts.value = it; _error.value = null }
                .onFailure { _error.value = it.message ?: "Failed to load contacts" }
            _loading.value = false
        }
    }

    fun save(name: String, phone: String, email: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.save(name, phone, email) }
                .onSuccess { refresh() }
                .onFailure { _error.value = it.message ?: "Failed to save contact" }
        }
    }

    fun delete(id: String?) {
        if (id.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess { refresh() }
                .onFailure { _error.value = it.message ?: "Failed to delete contact" }
        }
    }

    fun exportVcf() {
        viewModelScope.launch {
            runCatching { repository.exportVcf() }
                .onSuccess { _vcf.value = it }
                .onFailure { _error.value = it.message ?: "Failed to export" }
        }
    }

    fun clearVcf() {
        _vcf.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
