package ru.amigo.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.amigo.chat.data.repository.ChatRepository
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    repository: ChatRepository,
) : ViewModel() {

    val configured: StateFlow<Boolean?> = repository.isConfigured
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val serverUrl: StateFlow<String?> = repository.serverUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
