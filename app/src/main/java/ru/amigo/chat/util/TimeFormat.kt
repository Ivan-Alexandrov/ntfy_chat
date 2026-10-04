package ru.amigo.chat.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormat {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
    private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru"))

    fun parse(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { java.time.OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
    }

    fun messageTime(iso: String?): String {
        val instant = parse(iso) ?: return ""
        val zoned = instant.atZone(ZoneId.systemDefault())
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (zoned.toLocalDate()) {
            today -> timeFormatter.format(zoned)
            today.minusDays(1) -> "вчера ${timeFormatter.format(zoned)}"
            else -> dateFormatter.format(zoned)
        }
    }

    fun chatTime(iso: String?): String = messageTime(iso)

    fun full(iso: String?): String {
        val instant = parse(iso) ?: return ""
        return fullFormatter.format(instant.atZone(ZoneId.systemDefault()))
    }

    fun fromEpochSeconds(seconds: Long): String {
        if (seconds <= 0L) return ""
        val zoned = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (zoned.toLocalDate()) {
            today -> timeFormatter.format(zoned)
            today.minusDays(1) -> "вчера ${timeFormatter.format(zoned)}"
            else -> dateFormatter.format(zoned)
        }
    }
}
