package ru.amigo.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.amigo.chat.data.model.LocalChat
import ru.amigo.chat.data.repository.ChatRepository
import ru.amigo.chat.util.DeepLink
import ru.amigo.chat.util.DeepLinkPayload
import javax.inject.Inject

@HiltViewModel
class DeepLinkViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _openChat = MutableStateFlow<LocalChat?>(null)
    val openChat: StateFlow<LocalChat?> = _openChat.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pending: DeepLinkPayload? = null
    private var processing = false

    fun handleUri(uri: String?) {
        val payload = DeepLink.parse(uri) ?: return
        pending = payload
        process()
    }

    /** Обработать отложенный диплинк (например, после настройки сервера). */
    fun process() {
        val payload = pending ?: return
        if (processing) return
        processing = true
        viewModelScope.launch {
            runCatching {
                val current = repository.serverUrl.first()
                if (current.isNullOrBlank() || current != payload.s) {
                    repository.configure(payload.s, repository.currentToken().orEmpty())
                }
                val existing = repository.findChat(payload.t)
                if (existing != null) {
                    if (!payload.p.isNullOrBlank() && existing.encrypted &&
                        !repository.loadRememberedKey(existing.topic)
                    ) {
                        if (!repository.unlockFromServer(existing, payload.p)) {
                            throw IllegalArgumentException("Неверный пароль")
                        }
                    }
                    repository.findChat(payload.t) ?: existing
                } else {
                    repository.addChat(payload.t, payload.n, payload.p)
                }
            }
                .onSuccess { chat ->
                    pending = null
                    _openChat.value = chat
                }
                .onFailure { error ->
                    _error.value = error.message ?: "Не удалось добавить чат"
                }
            processing = false
        }
    }

    fun consumeOpen() {
        _openChat.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
