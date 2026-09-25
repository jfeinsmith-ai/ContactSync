package com.contactsync.app.domain

import java.net.URI
import java.text.Normalizer
import java.util.Locale

object Normalizers {
    private val whitespace = Regex("\\s+")
    private val linkedinSlug = Regex("[a-zA-Z0-9%._~-]+")

    fun name(value: String?): String? = value
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        ?.trim()
        ?.replace(whitespace, " ")
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)

    fun structuredName(given: String?, family: String?): String? {
        val first = name(given) ?: return null
        val last = name(family) ?: return null
        return "$first\u0000$last"
    }

    fun email(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (normalized.any(Char::isWhitespace)) return null
        if (normalized.count { it == '@' } != 1) return null
        val (local, domain) = normalized.split('@', limit = 2)
        if (local.isEmpty() || domain.isEmpty() || domain.startsWith('.') || domain.endsWith('.')) {
            return null
        }
        if (!domain.contains('.')) return null
        return normalized
    }

    fun linkedInPersonUrl(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return null
        if (host != "linkedin.com" && !host.endsWith(".linkedin.com")) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val inIndex = when {
            segments.size == 2 && segments[0].equals("in", ignoreCase = true) -> 0
            segments.size == 3 && segments[0].length == 2 &&
                segments[1].equals("in", ignoreCase = true) -> 1
            else -> return null
        }
        val slug = segments[inIndex + 1]
        if (!linkedinSlug.matches(slug)) return null
        return "linkedin.com/in/${slug.lowercase(Locale.ROOT)}"
    }
}

