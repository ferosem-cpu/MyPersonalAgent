package com.mypersonalagent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val avatarUri: StateFlow<String?> = settings.avatarUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setAvatarUri(uri: String) {
        viewModelScope.launch { settings.setAvatarUri(uri) }
    }
}
