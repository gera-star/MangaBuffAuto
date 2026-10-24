package com.example.myapplication.web

import java.net.URI

/**
 * Accepts only unambiguous HTTPS URLs. The app disables cleartext traffic, so accepting HTTP
 * here would produce a URL the WebView cannot safely open and confuse the user.
 */
internal object AccountWebUrlPolicy {
    fun normalize(raw: String): String? {
        val candidate = raw.trim().ifBlank { "https://mangabuff.ru/" }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        if (uri.port !in -1..65535 || uri.port == 0) return null
        val authority = uri.rawAuthority ?: return null
        if (authority.isBlank() || authority.endsWith(":")) return null
        if (uri.host.any { it == '_' || it.isWhitespace() }) return null
        return uri.toASCIIString()
    }
}
