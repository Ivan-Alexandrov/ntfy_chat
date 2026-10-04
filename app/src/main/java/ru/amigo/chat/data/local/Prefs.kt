package ru.amigo.chat.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "ntfy_chat")

class Prefs(private val context: Context) {

    private val serverKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("access_token")
    private val chatsKey = stringPreferencesKey("chats")
    private val deviceKey = stringPreferencesKey("device_id")
    private val savedKeysKey = stringPreferencesKey("saved_keys")
    private val systemTopicKey = stringPreferencesKey("system_topic")
    private val systemPasswordKey = stringPreferencesKey("system_password")
    private val storageModeKey = stringPreferencesKey("storage_mode")
    private val displayNameKey = stringPreferencesKey("display_name")

    val serverUrlFlow = context.dataStore.data.map { it[serverKey] }
    val tokenFlow = context.dataStore.data.map { it[tokenKey] }
    val chatsJsonFlow = context.dataStore.data.map { it[chatsKey] }
    val deviceIdFlow = context.dataStore.data.map { it[deviceKey] }
    val systemTopicFlow = context.dataStore.data.map { it[systemTopicKey] }
    val systemPasswordFlow = context.dataStore.data.map { it[systemPasswordKey] }
    val storageModeFlow = context.dataStore.data.map { it[storageModeKey] ?: "local" }
    val displayNameFlow = context.dataStore.data.map { it[displayNameKey] }

    suspend fun currentServerUrl(): String? = context.dataStore.data.first()[serverKey]
    suspend fun currentToken(): String? = context.dataStore.data.first()[tokenKey]
    suspend fun currentChatsJson(): String? = context.dataStore.data.first()[chatsKey]
    suspend fun currentSavedKeys(): String? = context.dataStore.data.first()[savedKeysKey]
    suspend fun currentSystemTopic(): String? = context.dataStore.data.first()[systemTopicKey]
    suspend fun currentSystemPassword(): String? = context.dataStore.data.first()[systemPasswordKey]
    suspend fun currentStorageMode(): String =
        context.dataStore.data.first()[storageModeKey] ?: "local"

    suspend fun currentDisplayName(): String? = context.dataStore.data.first()[displayNameKey]

    suspend fun setServer(url: String) {
        context.dataStore.edit { it[serverKey] = url }
    }

    suspend fun setToken(token: String) {
        context.dataStore.edit { it[tokenKey] = token }
    }

    suspend fun saveChats(json: String) {
        context.dataStore.edit { it[chatsKey] = json }
    }

    suspend fun saveSavedKeys(json: String) {
        context.dataStore.edit { it[savedKeysKey] = json }
    }

    suspend fun setSystem(topic: String?, password: String?) {
        context.dataStore.edit { prefs ->
            if (topic == null) prefs.remove(systemTopicKey) else prefs[systemTopicKey] = topic
            if (password == null) prefs.remove(systemPasswordKey) else prefs[systemPasswordKey] = password
        }
    }

    suspend fun setStorageMode(mode: String) {
        context.dataStore.edit { it[storageModeKey] = mode }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { prefs ->
            val trimmed = name.trim()
            if (trimmed.isBlank()) prefs.remove(displayNameKey) else prefs[displayNameKey] = trimmed
        }
    }

    suspend fun ensureDeviceId(): String {
        val existing = context.dataStore.data.first()[deviceKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        context.dataStore.edit { it[deviceKey] = generated }
        return generated
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
