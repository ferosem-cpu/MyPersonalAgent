package com.mypersonalagent.app.ui.roster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.local.AssistantEntity
import com.mypersonalagent.app.data.repo.AssistantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RosterViewModel @Inject constructor(
    private val assistants: AssistantRepository,
) : ViewModel() {

    val roster: StateFlow<List<AssistantEntity>> = assistants.assistants.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    init {
        viewModelScope.launch { assistants.ensureSeeded() }
    }

    fun create(
        name: String,
        title: String,
        instructions: String,
        colorHex: String,
        shape: String,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val bot = assistants.create(name, title, instructions, colorHex, shape)
            onCreated(bot.id)
        }
    }
}
