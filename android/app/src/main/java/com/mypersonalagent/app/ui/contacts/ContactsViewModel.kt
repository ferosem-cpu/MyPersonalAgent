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

    fun clearError() {
        _error.value = null
    }
}
