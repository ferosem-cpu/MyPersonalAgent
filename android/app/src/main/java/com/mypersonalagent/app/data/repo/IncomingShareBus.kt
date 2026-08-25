package com.mypersonalagent.app.data.repo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingShareBus @Inject constructor() {
    private val _ingested = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val ingested: SharedFlow<String> = _ingested.asSharedFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    fun emit(message: String) {
        _lastMessage.value = message
        _ingested.tryEmit(message)
    }

    fun clearMessage() {
        _lastMessage.value = null
    }
}
