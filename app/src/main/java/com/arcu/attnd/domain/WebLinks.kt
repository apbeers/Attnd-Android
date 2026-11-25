package com.arcu.attnd.domain

import java.net.URI

/** Validation of web links the server sends (API-016 `website_url`). */
object WebLinks {
    /**
     * [raw] trimmed when it is an absolute `https` URL with a host, else null
     * (missing, blank, `http`, other schemes, relative or malformed values).
     */
    fun httpsUrl(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        return text
    }

    /** The host of an absolute URL, or null. */
    fun host(url: String): String? = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
