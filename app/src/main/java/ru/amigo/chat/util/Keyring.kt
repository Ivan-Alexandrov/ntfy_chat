package ru.amigo.chat.util

import java.util.concurrent.ConcurrentHashMap

/** Кэш производных ключей на время сессии (пароль не сохраняется). */
object Keyring {

    private val keys = ConcurrentHashMap<String, ByteArray>()

    fun remember(topic: String, key: ByteArray) {
        keys[topic] = key
    }

    fun get(topic: String): ByteArray? = keys[topic]

    fun has(topic: String): Boolean = keys.containsKey(topic)

    fun forget(topic: String) {
        keys.remove(topic)
    }

    fun clear() {
        keys.clear()
    }
}
