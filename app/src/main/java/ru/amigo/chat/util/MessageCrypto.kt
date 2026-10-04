package ru.amigo.chat.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Лёгкое шифрование сообщений, совместимое с веб-клиентом.
 * Формат: "ENC1:" + base64(iv) + ":" + base64(ciphertext||tag).
 * PBKDF2-HMAC-SHA256 (100_000 итераций), ключ AES-256-GCM,
 * соль = SHA-256(topic).
 */
object MessageCrypto {

    private const val PREFIX = "ENC1:"
    const val DEFAULT_ITERATIONS = 600_000
    const val LEGACY_ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    fun isEncrypted(text: String?): Boolean = text != null && text.startsWith(PREFIX)

    fun deriveKey(password: String, topic: String, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(topic.toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    fun encrypt(key: ByteArray, plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(key: ByteArray, payload: String): String {
        val body = payload.removePrefix(PREFIX)
        val separator = body.indexOf(':')
        require(separator > 0) { "Некорректный шифротекст" }
        val iv = Base64.decode(body.substring(0, separator), Base64.NO_WRAP)
        val data = Base64.decode(body.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    fun tryDecrypt(key: ByteArray, payload: String): String? =
        runCatching { decrypt(key, payload) }.getOrNull()
}
