package ru.amigo.chat.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.amigo.chat.data.local.Prefs
import ru.amigo.chat.data.model.LocalChat
import ru.amigo.chat.data.model.SystemChat
import ru.amigo.chat.data.model.SystemSnapshot
import ru.amigo.chat.data.remote.ntfy.NtfyClient
import ru.amigo.chat.data.remote.ntfy.NtfyMessage
import ru.amigo.chat.data.remote.ntfy.NtfyPublishRequest
import ru.amigo.chat.util.DeepLink
import ru.amigo.chat.util.Keyring
import ru.amigo.chat.util.MessageCrypto
import ru.amigo.chat.util.NtfyLink
import ru.amigo.chat.util.SecureStore
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val prefs: Prefs,
    private val client: NtfyClient,
    private val json: Json,
) {

    private val chatsSerializer = ListSerializer(LocalChat.serializer())
    private val savedKeysSerializer = MapSerializer(String.serializer(), String.serializer())

    val serverUrl: Flow<String?> = prefs.serverUrlFlow
    val accessToken: Flow<String?> = prefs.tokenFlow
    val isConfigured: Flow<Boolean> = prefs.serverUrlFlow.map { !it.isNullOrBlank() }
    val chats: Flow<List<LocalChat>> = prefs.chatsJsonFlow.map { decode(it) }
    val systemTopic: Flow<String?> = prefs.systemTopicFlow
    val systemPassword: Flow<String?> = prefs.systemPasswordFlow
    val isSystemConfigured: Flow<Boolean> = prefs.systemTopicFlow.map { !it.isNullOrBlank() }
    val storageMode: Flow<String> = prefs.storageModeFlow
    val displayName: Flow<String?> = prefs.displayNameFlow

    suspend fun configure(serverUrl: String, token: String) {
        val normalized = serverUrl.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }
        prefs.setServer(normalized)
        prefs.setToken(token.trim())
    }

    suspend fun reset() = prefs.reset()

    suspend fun currentToken(): String? = prefs.currentToken()

    suspend fun deviceTag(): String = "me-" + prefs.ensureDeviceId()

    suspend fun createChat(title: String, password: String): LocalChat {
        val topic = UUID.randomUUID().toString()
        if (password.isNotBlank() && password.length < MIN_PASSWORD_LENGTH) {
            throw IllegalArgumentException("Пароль слишком короткий: минимум $MIN_PASSWORD_LENGTH символов")
        }
        val effectivePassword = password.takeIf { it.isNotBlank() } ?: randomPassword()
        val key = MessageCrypto.deriveKey(effectivePassword, topic, MessageCrypto.DEFAULT_ITERATIONS)
        Keyring.remember(topic, key)
        val now = nowSeconds()
        client.publish(
            baseUrl = base(),
            token = token(),
            request = NtfyPublishRequest(
                topic = topic,
                message = MessageCrypto.encrypt(key, "#$title"),
                markdown = false,
                tags = listOf(deviceTag()),
            ),
        )
        val chat = LocalChat(
            topic = topic,
            title = title,
            lastMessage = title,
            lastTime = now,
            lastReadTime = now,
            encrypted = true,
            password = effectivePassword,
            kdf = MessageCrypto.DEFAULT_ITERATIONS,
        )
        persist(decode(prefs.currentChatsJson()) + chat)
        rememberChatKey(topic)
        if (isCloudMode()) runCatching { publishSystem() }
        return chat
    }

    suspend fun addChat(input: String, titleInput: String?, password: String?): LocalChat {
        val parsed = NtfyLink.parse(input).getOrThrow()
        val server = base()
        val host = NtfyLink.hostOf(server)
        if (parsed.host != null && host != null && !parsed.host.equals(host, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Ссылка указывает на другой сервер (${parsed.host}). Смените сервер в настройках.",
            )
        }
        val list = decode(prefs.currentChatsJson())
        if (list.any { it.topic == parsed.topic }) {
            throw IllegalArgumentException("Такой чат уже добавлен")
        }
        val rawMessages = runCatching { client.history(server, token(), parsed.topic, "all") }
            .getOrDefault(emptyList())
            .filter { it.event == "message" }
        val encrypted = rawMessages.any { MessageCrypto.isEncrypted(it.message) }
        if (!encrypted) {
            throw IllegalArgumentException(
                "Топик не зашифрован — поддерживаются только зашифрованные чаты",
            )
        }

        var key: ByteArray? = null
        var kdf = MessageCrypto.DEFAULT_ITERATIONS
        if (encrypted && !password.isNullOrBlank()) {
            val sample = rawMessages.firstOrNull { MessageCrypto.isEncrypted(it.message) }
            val derived = deriveKeyForSample(password, parsed.topic, sample)
                ?: throw IllegalArgumentException("Неверный пароль")
            key = derived.first
            kdf = derived.second
            Keyring.remember(parsed.topic, key)
        }

        val decrypted = rawMessages.map { decrypt(it, key) }
        val last = decrypted.maxByOrNull { it.time }
        val title = titleInput?.trim()?.takeIf { it.isNotBlank() }
            ?: extractTitle(decrypted)
            ?: parsed.topic
        val now = nowSeconds()
        val chat = LocalChat(
            topic = parsed.topic,
            title = title,
            lastMessage = last?.let { preview(it, deviceTag()) } ?: "Нет сообщений",
            lastTime = last?.time ?: now,
            lastReadTime = now,
            encrypted = encrypted,
            password = password?.takeIf { it.isNotBlank() },
            kdf = kdf,
        )
        persist(list + chat)
        if (isCloudMode()) runCatching { publishSystem() }
        return chat
    }

    /** Проверить пароль и запомнить ключ. Возвращает false при неверном пароле. */
    suspend fun unlock(chat: LocalChat, password: String, rawMessages: List<NtfyMessage>): Boolean {
        val sample = rawMessages.firstOrNull { MessageCrypto.isEncrypted(it.message) }
        val derived = deriveKeyForSample(password, chat.topic, sample) ?: return false
        Keyring.remember(chat.topic, derived.first)
        updateChat(chat.copy(password = password, kdf = derived.second))
        return true
    }

    /**
     * Проверить пароль по истории сервера: если в топике нет шифротекста-образца,
     * пароль считается неподтверждённым, чтобы не открыть чат с неверным ключом.
     */
    suspend fun unlockFromServer(chat: LocalChat, password: String): Boolean {
        if (!chat.encrypted) return true
        val raw = runCatching { client.history(base(), token(), chat.topic, "all") }
            .getOrDefault(emptyList())
            .filter { it.event == "message" }
        val sample = raw.firstOrNull { MessageCrypto.isEncrypted(it.message) } ?: return false
        return unlock(chat, password, listOf(sample))
    }

    fun isLocked(chat: LocalChat): Boolean = chat.encrypted && !Keyring.has(chat.topic)

    suspend fun findChat(topic: String): LocalChat? =
        decode(prefs.currentChatsJson()).firstOrNull { it.topic == topic }

    /** Оставить только зашифрованные чаты (удаляет старые незашифрованные). */
    suspend fun pruneUnencrypted() {
        val list = decode(prefs.currentChatsJson())
        val filtered = list.filter { it.encrypted }
        if (filtered.size != list.size) {
            list.filterNot { it.encrypted }.forEach { Keyring.forget(it.topic) }
            persist(filtered)
        }
    }

    // --- системный топик со списком чатов ------------------------------------

    suspend fun createSystem(password: String): String {
        require(password.isNotBlank()) { "Введите пароль системного топика" }
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Пароль слишком короткий: минимум $MIN_PASSWORD_LENGTH символов"
        }
        val topic = UUID.randomUUID().toString()
        Keyring.remember(topic, MessageCrypto.deriveKey(password, topic, MessageCrypto.DEFAULT_ITERATIONS))
        prefs.setSystem(topic, password)
        publishSystem()
        return topic
    }

    /** Подключиться к существующему системному топику. false — неверный пароль. */
    suspend fun connectSystem(topic: String, password: String): Boolean {
        if (topic.isBlank() || password.isBlank()) return false
        val messages = runCatching { client.history(base(), token(), topic, "all") }
            .getOrDefault(emptyList())
            .filter { it.event == "message" }
        val sample = messages.firstOrNull { MessageCrypto.isEncrypted(it.message) }
        val derived = deriveKeyForSample(password, topic, sample) ?: return false
        Keyring.remember(topic, derived.first)
        prefs.setSystem(topic, password)
        syncSystem()
        return true
    }

    suspend fun disconnectSystem() {
        prefs.currentSystemTopic()?.let { Keyring.forget(it) }
        prefs.setSystem(null, null)
    }

    suspend fun setDisplayName(name: String) = prefs.setDisplayName(name)

    suspend fun currentDisplayName(): String? = prefs.currentDisplayName()?.takeIf { it.isNotBlank() }

    suspend fun setStorageMode(mode: String) {
        prefs.setStorageMode(mode)
        if (mode == "cloud") {
            if (isCloudMode()) runCatching { publishSystem() }
        }
    }

    private suspend fun isCloudMode(): Boolean = prefs.currentStorageMode() == "cloud"

    /** Синхронизировать из облака при старте, если выбран облачный режим. */
    suspend fun syncIfCloud() {
        if (isCloudMode() && !prefs.currentSystemTopic().isNullOrBlank()) {
            runCatching { syncSystem() }
        }
    }

    /** Опубликовать зашифрованный снимок списка чатов в системный топик. */
    suspend fun publishSystem() {
        val topic = prefs.currentSystemTopic() ?: return
        val key = Keyring.get(topic) ?: return
        val snapshot = SystemSnapshot(
            chats = decode(prefs.currentChatsJson()).map {
                SystemChat(it.topic, it.title, it.password, it.lastReadTime, it.encrypted, it.kdf)
            },
        )
        val payload = MessageCrypto.encrypt(
            key,
            json.encodeToString(SystemSnapshot.serializer(), snapshot),
        )
        client.publish(
            baseUrl = base(),
            token = token(),
            request = NtfyPublishRequest(
                topic = topic,
                message = payload,
                markdown = false,
                tags = listOf("system"),
            ),
        )
    }

    /** Последний расшифрованный снимок списка чатов (для отдельного экрана). */
    suspend fun systemSnapshot(): SystemSnapshot? {
        val topic = prefs.currentSystemTopic() ?: return null
        val key = Keyring.get(topic) ?: return null
        val messages = runCatching { client.history(base(), token(), topic, "all") }
            .getOrDefault(emptyList())
            .filter { it.event == "message" }
        val last = messages.maxByOrNull { it.time } ?: return null
        val plain = MessageCrypto.tryDecrypt(key, last.message) ?: return null
        return runCatching { json.decodeFromString(SystemSnapshot.serializer(), plain) }.getOrNull()
    }

    /** Применить снимок из системного топика к локальному списку чатов. */
    suspend fun syncSystem(): List<LocalChat> {
        val snapshot = systemSnapshot() ?: return decode(prefs.currentChatsJson())
        val existing = decode(prefs.currentChatsJson()).associateBy { it.topic }
        val merged = snapshot.chats.map { entry ->
            val old = existing[entry.topic]
            LocalChat(
                topic = entry.topic,
                title = entry.title,
                lastMessage = old?.lastMessage ?: "",
                lastTime = old?.lastTime ?: 0,
                lastReadTime = entry.lastReadTime,
                unread = old?.unread ?: 0,
                encrypted = true,
                password = entry.password,
                kdf = entry.kdf ?: MessageCrypto.LEGACY_ITERATIONS,
            )
        }
        persist(merged)
        merged.forEach { chat ->
            val password = chat.password
            if (!password.isNullOrBlank() && !Keyring.has(chat.topic)) {
                Keyring.remember(
                    chat.topic,
                    MessageCrypto.deriveKey(password, chat.topic, chat.kdf),
                )
                rememberChatKey(chat.topic)
            }
        }
        return merged
    }

    // --- «запомнить пароль» (ключ под Android Keystore) ----------------------

    suspend fun rememberChatKey(topic: String): Boolean {
        val key = Keyring.get(topic) ?: return false
        val blob = SecureStore.wrap(key) ?: return false
        val keys = savedKeys()
        keys[topic] = blob
        prefs.saveSavedKeys(json.encodeToString(savedKeysSerializer, keys))
        return true
    }

    suspend fun forgetChatKey(topic: String) {
        val keys = savedKeys()
        if (keys.remove(topic) != null) {
            prefs.saveSavedKeys(json.encodeToString(savedKeysSerializer, keys))
        }
        Keyring.forget(topic)
    }

    /** Загрузить сохранённый ключ чата в память. true — если удалось. */
    suspend fun loadRememberedKey(topic: String): Boolean {
        if (Keyring.has(topic)) return true
        val blob = savedKeys()[topic] ?: return false
        val key = SecureStore.unwrap(blob) ?: return false
        Keyring.remember(topic, key)
        return true
    }

    private suspend fun savedKeys(): MutableMap<String, String> {
        val raw = prefs.currentSavedKeys() ?: return mutableMapOf()
        return runCatching { json.decodeFromString(savedKeysSerializer, raw).toMutableMap() }
            .getOrDefault(mutableMapOf())
    }

    private suspend fun ensureKey(chat: LocalChat) {
        if (chat.encrypted && !Keyring.has(chat.topic)) loadRememberedKey(chat.topic)
    }

    suspend fun send(chat: LocalChat, text: String): NtfyMessage? {
        val key = if (chat.encrypted) {
            Keyring.get(chat.topic) ?: throw IllegalStateException("Чат зашифрован — введите пароль")
        } else {
            null
        }
        val payload = if (key != null) MessageCrypto.encrypt(key, text) else text
        val name = currentDisplayName()
        val title = name?.let { if (key != null) MessageCrypto.encrypt(key, it) else it }
        val now = nowSeconds()
        val response = client.publish(
            baseUrl = base(),
            token = token(),
            request = NtfyPublishRequest(
                topic = chat.topic,
                message = payload,
                title = title,
                markdown = key == null,
                tags = listOf(deviceTag()),
            ),
        )
        updateChat(chat.copy(lastMessage = firstLine(text), lastTime = now, lastReadTime = now, unread = 0))
        return response?.copy(
            plain = if (key != null) text else null,
            encrypted = key != null,
            sender = name,
        )
    }

    suspend fun messages(chat: LocalChat): List<NtfyMessage> {
        ensureKey(chat)
        val key = if (chat.encrypted) Keyring.get(chat.topic) else null
        return client.history(base(), token(), chat.topic, "all")
            .filter { it.event == "message" }
            .map { decrypt(it, key) }
            .sortedBy { it.time }
    }

    suspend fun subscribe(chat: LocalChat): Flow<NtfyMessage> {
        val baseUrl = base()
        val tk = token()
        return client.subscribe(baseUrl, tk, chat.topic, "all").map { message ->
            val key = if (chat.encrypted) Keyring.get(chat.topic) else null
            decrypt(message, key)
        }
    }

    suspend fun refreshAll(): List<LocalChat> {
        val list = decode(prefs.currentChatsJson())
        if (list.isEmpty()) return emptyList()
        val tag = deviceTag()
        val baseUrl = base()
        val tk = token()
        val updated = list.map { chat ->
            val since = if (chat.lastTime > 0) chat.lastTime.toString() else "all"
            val messages = runCatching { client.history(baseUrl, tk, chat.topic, since) }
                .getOrDefault(emptyList())
                .filter { it.event == "message" }
            val last = messages.maxByOrNull { it.time } ?: return@map chat
            val unreadAdd = messages.count { it.time > chat.lastReadTime && !it.tags.contains(tag) }
            if (chat.encrypted && !Keyring.has(chat.topic)) loadRememberedKey(chat.topic)
            val key = if (chat.encrypted) Keyring.get(chat.topic) else null
            val lastDecrypted = decrypt(last, key)
            chat.copy(
                lastMessage = preview(lastDecrypted, tag),
                lastTime = last.time,
                unread = chat.unread + unreadAdd,
            )
        }
        if (updated != list) persist(updated)
        return updated.sortedByDescending { it.lastTime }
    }

    suspend fun markRead(chat: LocalChat) {
        updateChat(chat.copy(lastReadTime = nowSeconds(), unread = 0))
    }

    suspend fun deleteChat(chat: LocalChat) {
        forgetChatKey(chat.topic)
        persist(decode(prefs.currentChatsJson()).filterNot { it.topic == chat.topic })
        if (isCloudMode()) runCatching { publishSystem() }
    }

    // --- helpers -------------------------------------------------------------

    /** Диплинк на добавление чата (без пароля — его вводят вручную). */
    suspend fun deepLink(chat: LocalChat): String =
        DeepLink.build(base(), chat.topic, chat.title, null)

    fun extractTitle(messages: List<NtfyMessage>): String? =
        messages.firstOrNull { isTitleMessage(it) }
            ?.let { display(it) }
            ?.replace(Regex("^#+\\s*"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun isTitleMessage(message: NtfyMessage): Boolean {
        val text = (display(message) ?: "").trimStart()
        return text.startsWith("#") && !text.startsWith("##")
    }

    fun isLockedMessage(message: NtfyMessage): Boolean =
        MessageCrypto.isEncrypted(message.message) && message.plain == null

    private fun display(message: NtfyMessage): String? =
        if (MessageCrypto.isEncrypted(message.message)) message.plain else message.message

    private fun decrypt(message: NtfyMessage, key: ByteArray?): NtfyMessage {
        val sender = message.title?.let { title ->
            if (MessageCrypto.isEncrypted(title)) key?.let { MessageCrypto.tryDecrypt(it, title) } else title
        }?.takeIf { it.isNotBlank() }
        if (!MessageCrypto.isEncrypted(message.message)) return message.copy(sender = sender)
        val plain = key?.let { MessageCrypto.tryDecrypt(it, message.message) }
        return message.copy(plain = plain, encrypted = true, sender = sender)
    }

    private suspend fun updateChat(chat: LocalChat) {
        val list = decode(prefs.currentChatsJson()).toMutableList()
        val index = list.indexOfFirst { it.topic == chat.topic }
        if (index >= 0) list[index] = chat else list.add(chat)
        persist(list)
    }

    private suspend fun persist(list: List<LocalChat>) {
        prefs.saveChats(json.encodeToString(chatsSerializer, list))
    }

    private fun decode(raw: String?): List<LocalChat> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(chatsSerializer, raw) }.getOrDefault(emptyList())
    }

    private suspend fun base(): String = prefs.currentServerUrl()?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER

    private suspend fun token(): String? = prefs.currentToken()?.takeIf { it.isNotBlank() }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Подобрать число итераций PBKDF2: по умолчанию новое, при наличии образца
     * шифротекста пробуем новое, затем устаревшее (100k) для старых чатов.
     * null — пароль не подходит.
     */
    private fun deriveKeyForSample(
        password: String,
        topic: String,
        sample: NtfyMessage?,
    ): Pair<ByteArray, Int>? {
        val encryptedSample = sample?.takeIf { MessageCrypto.isEncrypted(it.message) }
        val candidates = if (encryptedSample != null) {
            listOf(MessageCrypto.DEFAULT_ITERATIONS, MessageCrypto.LEGACY_ITERATIONS)
        } else {
            listOf(MessageCrypto.DEFAULT_ITERATIONS)
        }
        for (iterations in candidates) {
            val key = MessageCrypto.deriveKey(password, topic, iterations)
            if (encryptedSample == null || MessageCrypto.tryDecrypt(key, encryptedSample.message) != null) {
                return key to iterations
            }
        }
        return null
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull()?.trim().orEmpty()

    private fun preview(message: NtfyMessage, tag: String): String {
        val text = if (isLockedMessage(message)) {
            "🔒 Зашифровано"
        } else {
            (display(message) ?: "")
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
                .ifBlank { "Вложение" }
        }
        return if (message.tags.contains(tag)) "Вы: $text" else text
    }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        const val MIN_PASSWORD_LENGTH = 12
    }
}
