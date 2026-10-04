package ru.amigo.chat.data.remote.ntfy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NtfyClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun publish(
        baseUrl: String,
        token: String?,
        request: NtfyPublishRequest,
    ): NtfyMessage? = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/"
        val body = json.encodeToString(NtfyPublishRequest.serializer(), request).toRequestBody(jsonMedia)
        val builder = Request.Builder().url(url).post(body)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw NtfyException(response.code, text)
            runCatching { json.decodeFromString(NtfyMessage.serializer(), text) }.getOrNull()
        }
    }

    suspend fun history(
        baseUrl: String,
        token: String?,
        topic: String,
        since: String = "all",
    ): List<NtfyMessage> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/$topic/json?poll=1&since=$since"
        val builder = Request.Builder().url(url).get()
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw NtfyException(response.code, text)
            text.lineSequence()
                .mapNotNull { parseMessage(it) }
                .filter { it.event == "message" }
                .toList()
        }
    }

    fun subscribe(
        baseUrl: String,
        token: String?,
        topic: String,
        since: String = "all",
    ): Flow<NtfyMessage> = callbackFlow {
        val builder = Request.Builder().url(webSocketUrl(baseUrl, topic, since))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val socket = client.newWebSocket(
            builder.build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = parseMessage(text) ?: return
                    if (event.event == "message") {
                        trySend(event)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )
        awaitClose { socket.close(1000, null) }
    }

    private fun parseMessage(line: String): NtfyMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.decodeFromString(NtfyMessage.serializer(), trimmed) }.getOrNull()
    }

    private fun webSocketUrl(baseUrl: String, topic: String, since: String): String {
        val wsBase = baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$wsBase/$topic/ws?since=$since"
    }
}
