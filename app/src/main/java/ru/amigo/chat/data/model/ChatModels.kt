package ru.amigo.chat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LocalChat(
    val topic: String,
    val title: String,
    val lastMessage: String = "",
    val lastTime: Long = 0,
    val lastReadTime: Long = 0,
    val unread: Int = 0,
    val encrypted: Boolean = false,
    val password: String? = null,
    val kdf: Int = 100_000,
)

@Serializable
data class SystemChat(
    val topic: String,
    val title: String,
    val password: String? = null,
    val lastReadTime: Long = 0,
    val encrypted: Boolean = true,
    val kdf: Int? = null,
)

@Serializable
data class SystemSnapshot(
    val v: Int = 1,
    val chats: List<SystemChat> = emptyList(),
)
