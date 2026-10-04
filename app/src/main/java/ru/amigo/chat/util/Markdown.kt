package ru.amigo.chat.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val LinkColor = Color(0xFF3B5BFF)
private val CodeBackground = Color(0x22000000)

private val horizontalRule = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val heading = Regex("^(#{1,6})\\s*(.*)$")
private val unorderedList = Regex("^\\s*[-*+]\\s+(.*)$")
private val orderedList = Regex("^\\s*(\\d+\\.)\\s+(.*)$")

/**
 * Минимальный рендер Markdown в AnnotatedString:
 * заголовки, **жирный**, *курсив*, `код`, ~~зачёркнутый~~, [ссылки](url),
 * списки, цитаты, горизонтальные линии.
 */
fun markdownToAnnotatedString(
    text: String,
    baseSize: TextUnit = 15.sp,
): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { index, line ->
        if (index > 0) append("\n")
        when {
            horizontalRule.matches(line) -> append("──────────")

            else -> {
                val h = heading.find(line)
                val ul = unorderedList.find(line)
                val ol = orderedList.find(line)
                when {
                    h != null -> {
                        val level = h.groupValues[1].length
                        val size = when (level) {
                            1 -> baseSize * 1.5f
                            2 -> baseSize * 1.3f
                            3 -> baseSize * 1.15f
                            else -> baseSize
                        }
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
                            appendInline(h.groupValues[2])
                        }
                    }

                    line.trimStart().startsWith(">") -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF8A8A8A))) {
                            append("▎ ")
                            appendInline(line.trimStart().trimStart('>').trimStart())
                        }
                    }

                    ul != null -> {
                        append("•  ")
                        appendInline(ul.groupValues[1])
                    }

                    ol != null -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(ol.groupValues[1])
                        }
                        append("  ")
                        appendInline(ol.groupValues[2])
                    }

                    else -> appendInline(line)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty()) {
            append(buffer.toString())
            buffer.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    buffer.append(text[i]); i++
                }
            }

            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInline(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    buffer.append(text[i]); i++
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = CodeBackground,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    buffer.append(text[i]); i++
                }
            }

            text[i] == '*' || text[i] == '_' -> {
                val end = text.indexOf(text[i], i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    buffer.append(text[i]); i++
                }
            }

            text[i] == '[' -> {
                val close = text.indexOf("](", i + 1)
                val urlEnd = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && urlEnd > close) {
                    flush()
                    withStyle(
                        SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline),
                    ) {
                        append(text.substring(i + 1, close))
                    }
                    i = urlEnd + 1
                } else {
                    buffer.append(text[i]); i++
                }
            }

            else -> {
                buffer.append(text[i]); i++
            }
        }
    }
    flush()
}
