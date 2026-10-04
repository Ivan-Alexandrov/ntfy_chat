package ru.amigo.chat.util

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeepLinkPayload(
    val s: String,
    val t: String,
    val n: String? = null,
    val p: String? = null,
)

/**
 * Диплинк на добавление чата: amigo-chat://add?chat=<base64url(JSON)>.
 * Полезная нагрузка: s — сервер, t — топик, n — название, p — пароль.
 */
object DeepLink {

    const val SCHEME = "amigo-chat"
    private const val HOST = "add"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun build(server: String, topic: String, title: String?, password: String?): String {
        val payload = DeepLinkPayload(s = server, t = topic, n = title, p = password)
        val encoded = json.encodeToString(DeepLinkPayload.serializer(), payload)
        val base64 = Base64.encodeToString(encoded.toByteArray(Charsets.UTF_8), FLAGS)
        return "$SCHEME://$HOST?chat=$base64"
    }

    fun parse(uri: String?): DeepLinkPayload? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!parsed.host.equals(HOST, ignoreCase = true)) return null
        val raw = parsed.getQueryParameter("chat") ?: return null
        return runCatching {
            val decoded = String(Base64.decode(raw, FLAGS), Charsets.UTF_8)
            json.decodeFromString(DeepLinkPayload.serializer(), decoded)
        }.getOrNull()
    }
}
