package ru.amigo.chat.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.amigo.chat.data.model.LocalChat
import ru.amigo.chat.data.repository.ChatRepository
import ru.amigo.chat.util.toMessage
import javax.inject.Inject

data class ChatListUiState(
    val loading: Boolean = false,
    val chats: List<LocalChat> = emptyList(),
    val error: String? = null,
    val creating: Boolean = false,
    val adding: Boolean = false,
    val server: String = ChatRepository.DEFAULT_SERVER,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatListUiState())
    val state: StateFlow<ChatListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.pruneUnencrypted()
        }
        viewModelScope.launch {
            repository.syncIfCloud()
        }
        viewModelScope.launch {
            repository.chats.collect { list ->
                _state.update { it.copy(chats = list.sortedByDescending { chat -> chat.lastTime }) }
            }
        }
        viewModelScope.launch {
            val server = repository.serverUrl.first()
            if (!server.isNullOrBlank()) {
                _state.update { it.copy(server = server) }
            }
        }
        refresh()
    }

    fun buildShareText(chat: LocalChat, onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(repository.deepLink(chat)) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.chats.isEmpty(), error = null) }
            runCatching { repository.refreshAll() }
                .onSuccess { list ->
                    _state.update { it.copy(loading = false, chats = list) }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.toMessage()) }
                }
        }
    }

    fun createChat(title: String, password: String, onCreated: (LocalChat) -> Unit) {
        if (title.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            runCatching { repository.createChat(title.trim(), password) }
                .onSuccess { chat ->
                    _state.update { it.copy(creating = false) }
                    onCreated(chat)
                }
                .onFailure { error ->
                    _state.update { it.copy(creating = false, error = error.toMessage()) }
                }
        }
    }

    fun deleteChat(chat: LocalChat) {
        viewModelScope.launch { repository.deleteChat(chat) }
    }

    fun addChatByLink(
        input: String,
        title: String,
        password: String,
        remember: Boolean,
        onAdded: (LocalChat) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            runCatching { repository.addChat(input, title.ifBlank { null }, password.ifBlank { null }) }
                .onSuccess { chat ->
                    if (remember && password.isNotBlank()) repository.rememberChatKey(chat.topic)
                    _state.update { it.copy(adding = false) }
                    onAdded(chat)
                }
                .onFailure { error ->
                    _state.update { it.copy(adding = false) }
                    onError(error.message ?: "Не удалось добавить чат")
                }
        }
    }
}
