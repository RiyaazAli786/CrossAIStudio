package com.pintoinsta.app.util

import android.os.Build
import android.text.Html
import androidx.core.text.HtmlCompat
import java.text.Normalizer

/**
 * Utility for handling, sanitizing, and properly escaping captions
 * containing special characters, HTML entities, and Unicode emojis
 * for Instagram posting and JSON payloads.
 */
object InstagramCaptionUtils {

    const val MAX_INSTAGRAM_CAPTION_LENGTH = 2200

    /**
     * Cleans and formats a caption for Instagram:
     * 1. Decodes HTML entities (e.g. &amp;, &#39;, &quot;, &#128525;)
     * 2. Canonicalizes Unicode characters to NFC format
     * 3. Sanitizes dangerous non-printable ASCII control characters
     * 4. Normalizes newlines and whitespace
     * 5. Safely enforces Instagram's 2,200 character limit without breaking surrogate pairs
     */
    fun cleanAndFormatCaption(rawCaption: String?): String {
        if (rawCaption.isNullOrBlank()) return ""

        // Step 1: Decode HTML entities
        var decoded = decodeHtmlEntities(rawCaption)

        // Step 2: Normalize Unicode (NFC form)
        decoded = Normalizer.normalize(decoded, Normalizer.Form.NFC)

        // Step 3: Normalize newlines (\r\n and \r -> \n)
        decoded = decoded.replace("\r\n", "\n").replace("\r", "\n")

        // Step 4: Sanitize non-printable control characters while preserving emojis and valid scripts
        val sanitized = StringBuilder(decoded.length)
        var i = 0
        while (i < decoded.length) {
            val c = decoded[i]
            when {
                // Keep newlines and tabs
                c == '\n' || c == '\t' -> {
                    sanitized.append(c)
                    i++
                }
                // Strip ASCII control characters 0x00..0x1F and 0x7F..0x9F
                (c.code in 0x00..0x1F) || (c.code in 0x7F..0x9F) -> {
                    i++
                }
                // Check for surrogate pairs (emojis above U+FFFF)
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < decoded.length && Character.isLowSurrogate(decoded[i + 1])) {
                        // Valid surrogate pair (e.g. 🔥, 😂, 🚀) - preserve both
                        sanitized.append(c)
                        sanitized.append(decoded[i + 1])
                        i += 2
                    } else {
                        // Orphaned high surrogate - skip to avoid corrupting UTF-8
                        i++
                    }
                }
                Character.isLowSurrogate(c) -> {
                    // Orphaned low surrogate - skip
                    i++
                }
                // Valid character (including Unicode symbols and non-Latin alphabets)
                else -> {
                    sanitized.append(c)
                    i++
                }
            }
        }

        var result = sanitized.toString()

        // Step 5: Replace 3+ consecutive newlines with 2 newlines (clean formatting)
        result = result.replace(Regex("\n{3,}"), "\n\n")

        // Step 6: Trim leading/trailing whitespace
        result = result.trim()

        // Step 7: Enforce Instagram's 2,200 character limit safely without slicing surrogate pairs
        if (result.length > MAX_INSTAGRAM_CAPTION_LENGTH) {
            result = safeTruncate(result, MAX_INSTAGRAM_CAPTION_LENGTH, appendEllipsis = false)
        }

        return result
    }

    /**
     * Decodes HTML entities (named, decimal numeric, and hex numeric).
     */
    fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text

        var working = text
        // Fast replace for common Pinterest entities
        working = working
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#x2F;", "/")
            .replace("&#47;", "/")

        if (!working.contains('&')) return working

        // Comprehensive HTML entity decoding via Android Html parser
        val htmlDecoded = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(working, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(working).toString()
            }
        } catch (_: Exception) {
            working
        }

        // Regex fallback for any unconverted numeric entities: &#128525; or &#x1F525;
        val entityRegex = Regex("&#([0-9]{1,7});|&#x([0-9a-fA-F]{1,6});")
        return entityRegex.replace(htmlDecoded) { matchResult ->
            try {
                val decCode = matchResult.groups[1]?.value
                val hexCode = matchResult.groups[2]?.value
                val codePoint = when {
                    decCode != null -> decCode.toInt(10)
                    hexCode != null -> hexCode.toInt(16)
                    else -> null
                }
                if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                    String(Character.toChars(codePoint))
                } else {
                    matchResult.value
                }
            } catch (_: Exception) {
                matchResult.value
            }
        }
    }

    /**
     * Truncates a string to [maxLength] characters without splitting surrogate pairs (emojis).
     * If [appendEllipsis] is true and text was truncated, appends "...".
     */
    fun safeTruncate(text: String, maxLength: Int, appendEllipsis: Boolean = false): String {
        if (text.length <= maxLength) return text

        var targetLen = if (appendEllipsis) (maxLength - 3).coerceAtLeast(1) else maxLength
        if (targetLen >= text.length) return text

        // If the character at targetLen is a low surrogate, step back so we don't split the pair
        if (Character.isLowSurrogate(text[targetLen])) {
            targetLen--
        }
        // If the character at targetLen - 1 is a high surrogate, step back to avoid dangling surrogate
        if (targetLen > 0 && Character.isHighSurrogate(text[targetLen - 1])) {
            targetLen--
        }

        val truncated = text.substring(0, targetLen).trimEnd()
        return if (appendEllipsis) "$truncated..." else truncated
    }

    /**
     * Safely escapes a string for JSON serialization.
     * Escapes quotes, backslashes, and control characters according to RFC 8259.
     *
     * @param text The text to escape.
     * @param escapeNonAscii If true, characters outside 0x20..0x7E (including emojis)
     *                       are encoded as \uXXXX or surrogate pairs \uD83D\uDD25.
     *                       If false, valid UTF-8 characters and emojis are kept as UTF-8.
     */
    fun escapeJsonString(text: String, escapeNonAscii: Boolean = false): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { sb.append("\\\""); i++ }
                c == '\\' -> { sb.append("\\\\"); i++ }
                c == '\b' -> { sb.append("\\b"); i++ }
                c == '\u000c' -> { sb.append("\\f"); i++ }
                c == '\n' -> { sb.append("\\n"); i++ }
                c == '\r' -> { sb.append("\\r"); i++ }
                c == '\t' -> { sb.append("\\t"); i++ }
                c.code in 0x00..0x1F -> {
                    sb.append(String.format("\\u%04x", c.code))
                    i++
                }
                escapeNonAscii && c.code > 0x7E -> {
                    if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        val high = c.code
                        val low = text[i + 1].code
                        sb.append(String.format("\\u%04x\\u%04x", high, low))
                        i += 2
                    } else {
                        sb.append(String.format("\\u%04x", c.code))
                        i++
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
