package ru.amigo.chat.util

import android.net.Uri

data class ParsedTopic(val topic: String, val host: String?)

object NtfyLink {

    private val topicRegex = Regex("^[-_A-Za-z0-9]{1,64}$")
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun parse(input: String): Result<ParsedTopic> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Введите топик или ссылку"))
        }
        val topic: String
        var host: String? = null
        if (schemeRegex.containsMatchIn(trimmed)) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("Некорректная ссылка"))
            host = uri.host
            topic = uri.pathSegments?.firstOrNull().orEmpty()
        } else {
            topic = trimmed.trimStart('/').substringBefore('/')
        }
        if (!topicRegex.matches(topic)) {
            return Result.failure(
                IllegalArgumentException(
                    "Некорректный топик: только буквы, цифры, «-» и «_» (до 64 символов)",
                ),
            )
        }
        return Result.success(ParsedTopic(topic, host))
    }

    fun hostOf(server: String): String? = runCatching { Uri.parse(server).host }.getOrNull()
}
