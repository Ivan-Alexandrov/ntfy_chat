package ru.amigo.chat.util

import ru.amigo.chat.data.remote.ntfy.NtfyException
import java.io.IOException

fun Throwable.toMessage(): String = when (this) {
    is NtfyException -> when (code) {
        401, 403 -> "Нет доступа к топику ntfy (проверьте токен в настройках)"
        404 -> "Топик не найден"
        429 -> "Слишком много запросов к ntfy, попробуйте позже"
        else -> "Ошибка ntfy (HTTP $code)"
    }
    is IOException -> "Нет соединения с сервером"
    else -> message ?: "Неизвестная ошибка"
}
