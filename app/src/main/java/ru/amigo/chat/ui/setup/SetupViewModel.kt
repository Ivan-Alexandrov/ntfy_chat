package ru.amigo.chat.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.amigo.chat.data.repository.ChatRepository
import javax.inject.Inject

data class SetupUiState(
    val serverUrl: String = ChatRepository.DEFAULT_SERVER,
    val token: String = "",
    val displayName: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = serverUrl.isNotBlank() && !loading
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val url = repository.serverUrl.first()
            val token = repository.accessToken.first()
            val name = repository.displayName.first()
            _state.update {
                it.copy(
                    serverUrl = url?.takeIf { value -> value.isNotBlank() } ?: ChatRepository.DEFAULT_SERVER,
                    token = token.orEmpty(),
                    displayName = name.orEmpty(),
                )
            }
        }
    }

    fun onServerChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }

    fun onTokenChange(value: String) = _state.update { it.copy(token = value, error = null) }

    fun onNameChange(value: String) = _state.update { it.copy(displayName = value, error = null) }

    fun save(onDone: () -> Unit) {
        val server = _state.value.serverUrl.trim()
        if (server.isBlank()) {
            _state.update { it.copy(error = "Укажите адрес сервера ntfy") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.configure(server, _state.value.token)
                repository.setDisplayName(_state.value.displayName)
            }
                .onSuccess {
                    _state.update { it.copy(loading = false) }
                    onDone()
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.message ?: "Ошибка") }
                }
        }
    }
}
