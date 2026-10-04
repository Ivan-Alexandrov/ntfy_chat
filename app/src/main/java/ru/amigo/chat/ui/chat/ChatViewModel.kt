package ru.amigo.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.amigo.chat.data.model.LocalChat
import ru.amigo.chat.data.remote.ntfy.NtfyMessage
import ru.amigo.chat.data.repository.ChatRepository
import ru.amigo.chat.util.toMessage
import javax.inject.Inject

data class ChatUiState(
    val loading: Boolean = true,
    val messages: List<NtfyMessage> = emptyList(),
    val deviceTag: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val encrypted: Boolean = false,
    val locked: Boolean = false,
    val unlocking: Boolean = false,
    val unlockError: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val topic: String = savedStateHandle.get<String>("topic").orEmpty()
    private val fallbackTitle: String = savedStateHandle.get<String>("title").orEmpty()

    private var chat: LocalChat = LocalChat(topic = topic, title = fallbackTitle)

    val title: String get() = chat.title.ifBlank { fallbackTitle }

    val password: String? get() = chat.password

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.findChat(topic)?.let { chat = it }
            if (chat.encrypted) repository.loadRememberedKey(chat.topic)
            _state.update {
                it.copy(encrypted = chat.encrypted, locked = repository.isLocked(chat))
            }
            loadInternal()
            subscribe()
        }
    }

    private fun loadInternal() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val tag = repository.deviceTag()
            runCatching { repository.messages(chat) }
                .onSuccess { messages ->
                    _state.update {
                        it.copy(
                            loading = false,
                            deviceTag = tag,
                            messages = messages.filterNot(repository::isTitleMessage),
                            locked = repository.isLocked(chat),
                        )
                    }
                    repository.markRead(chat)
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.toMessage()) }
                }
        }
    }

    private fun subscribe() {
        viewModelScope.launch {
            val tag = repository.deviceTag()
            _state.update { it.copy(deviceTag = tag) }
            while (isActive) {
                runCatching {
                    repository.subscribe(chat).collect { message -> onIncoming(message) }
                }
                if (!isActive) break
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun onIncoming(message: NtfyMessage) {
        if (repository.isTitleMessage(message)) return
        _state.update { current ->
            if (current.messages.any { it.id == message.id }) {
                current
            } else {
                current.copy(messages = (current.messages + message).sortedBy { it.time })
            }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (_state.value.locked) {
            _state.update { it.copy(unlockError = "Чтобы писать, введите пароль") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            runCatching { repository.send(chat, body) }
                .onSuccess { sent ->
                    if (sent != null) onIncoming(sent)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.toMessage(), locked = repository.isLocked(chat))
                    }
                }
            _state.update { it.copy(sending = false) }
        }
    }

    fun unlock(password: String, remember: Boolean) {
        if (password.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(unlocking = true, unlockError = null) }
            val ok = runCatching {
                repository.unlock(chat, password, _state.value.messages)
            }.getOrDefault(false)
            if (ok) {
                if (remember) repository.rememberChatKey(chat.topic)
                _state.update { it.copy(unlocking = false, unlockError = null, locked = false) }
                loadInternal()
            } else {
                _state.update { it.copy(unlocking = false, unlockError = "Неверный пароль") }
            }
        }
    }

    fun dismissUnlock() {
        _state.update { it.copy(unlockError = null) }
    }

    fun displayText(message: NtfyMessage): String =
        if (message.encrypted) message.plain.orEmpty() else message.message

    fun isLockedMessage(message: NtfyMessage): Boolean = repository.isLockedMessage(message)

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 3000L
    }
}
