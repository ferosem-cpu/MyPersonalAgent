package com.mypersonalagent.app.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.local.FileEntity
import com.mypersonalagent.app.data.repo.FileInboxRepository
import com.mypersonalagent.app.data.repo.IncomingShareBus
import com.mypersonalagent.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val inbox: FileInboxRepository,
    private val settings: SettingsRepository,
    private val shareBus: IncomingShareBus,
) : ViewModel() {

    val files: StateFlow<List<FileEntity>> = inbox.files
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val driveFolderUri: StateFlow<String?> = settings.driveFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastMessage: StateFlow<String?> = shareBus.lastMessage

    fun ingest(uri: Uri) {
        viewModelScope.launch {
            runCatching { inbox.ingestUri(uri, source = "picker") }
                .onFailure { shareBus.emit("Could not save file: ${it.message}") }
        }
    }

    fun setDriveFolder(uri: String) {
        viewModelScope.launch { settings.setDriveFolderUri(uri) }
    }

    fun clearMessage() = shareBus.clearMessage()
}
