package ru.amigo.chat.data.remote.ntfy

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class NtfyMessage(
    val id: String = "",
    val time: Long = 0,
    val event: String = "message",
    val topic: String = "",
    val message: String = "",
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val priority: Int? = null,
    val click: String? = null,
    val attachment: NtfyAttachment? = null,
    @Transient val plain: String? = null,
    @Transient val encrypted: Boolean = false,
    @Transient val sender: String? = null,
)

@Serializable
data class NtfyAttachment(
    val name: String = "",
    val url: String = "",
    val type: String? = null,
    val size: Long? = null,
)

@Serializable
data class NtfyPublishRequest(
    val topic: String,
    val message: String,
    val title: String? = null,
    val markdown: Boolean = true,
    val tags: List<String> = emptyList(),
    val priority: Int? = null,
)

class NtfyException(val code: Int, val body: String) :
    RuntimeException("ntfy HTTP $code: ${body.take(200)}")
