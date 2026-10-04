package ru.amigo.chat.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.amigo.chat.data.model.SystemChat
import ru.amigo.chat.data.repository.ChatRepository
import ru.amigo.chat.util.toMessage
import javax.inject.Inject

data class SystemUiState(
    val loading: Boolean = false,
    val configured: Boolean = false,
    val topic: String? = null,
    val password: String? = null,
    val mode: String = "local",
    val chats: List<SystemChat> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class SystemViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.systemTopic.collect { topic ->
                _state.update { it.copy(topic = topic, configured = !topic.isNullOrBlank()) }
            }
        }
        viewModelScope.launch {
            repository.systemPassword.collect { password ->
                _state.update { it.copy(password = password) }
            }
        }
        viewModelScope.launch {
            repository.storageMode.collect { mode ->
                _state.update { it.copy(mode = mode) }
            }
        }
        refresh()
    }

    fun setMode(mode: String) {
        viewModelScope.launch {
            repository.setStorageMode(mode)
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.topic.isNullOrBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.systemSnapshot() }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(loading = false, chats = snapshot?.chats ?: emptyList())
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.toMessage()) }
                }
        }
    }

    fun create(password: String) {
        if (password.isBlank()) {
            _state.update { it.copy(error = "Введите пароль системного топика") }
            return
        }
        viewModelScope.launch {
            runCatching { repository.createSystem(password) }
                .onFailure { error -> _state.update { it.copy(error = error.toMessage()) } }
            refresh()
        }
    }

    fun connect(topic: String, password: String) {
        viewModelScope.launch {
            val ok = runCatching { repository.connectSystem(topic.trim(), password) }
                .getOrDefault(false)
            if (!ok) {
                _state.update { it.copy(error = "Неверный пароль или топик") }
                return@launch
            }
            refresh()
        }
    }

    fun publish() {
        viewModelScope.launch {
            runCatching { repository.publishSystem() }
                .onFailure { error -> _state.update { it.copy(error = error.toMessage()) } }
            refresh()
        }
    }

    fun sync() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.syncSystem() }
                .onFailure { error -> _state.update { it.copy(error = error.toMessage()) } }
            refresh()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnectSystem()
            _state.update { it.copy(chats = emptyList()) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
