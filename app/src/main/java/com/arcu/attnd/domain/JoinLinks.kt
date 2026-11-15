package com.arcu.attnd.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parsing of the ways an event code reaches the app: App Links, QR codes and the Play install referrer. */
object JoinLinks {

    private const val HOST = "attnd.app"

    /**
     * Extracts the event code from `https://attnd.app/join/<code>/` (with or without `www.`,
     * trailing slash, query or fragment). A bare code (no scheme, no slash) is returned trimmed.
     * Returns null for any other URL.
     */
    fun parseJoinCode(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (!value.contains("/") && !value.contains(":")) return value

        val uri = try {
            URI(if (value.contains("://")) value else "https://$value")
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != HOST) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "join") return null
        // URI.path is already percent-decoded.
        return segments[1].trim().takeIf { it.isNotEmpty() }
    }

    /** A shared plan / item link (API-015): the event code and the still-encoded payload ([PlanShare.decode]). */
    data class ShareLink(val eventCode: String, val payload: String)

    /**
     * `https://attnd.app/p/<code>/<payload>` (with or without `www.`, a trailing slash, query or
     * fragment) → its code and payload; null for anything else (also `/p/<code>/<payload>/qr.svg`).
     */
    fun parseShareLink(raw: String?): ShareLink? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = try {
            URI(if (value.contains("://")) value else "https://$value")
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != HOST) return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size != 3 || segments[0] != "p") return null
        val code = decode(segments[1]).trim()
        val payload = segments[2].trim()
        if (code.isEmpty() || payload.isEmpty()) return null
        return ShareLink(code, payload)
    }

    /**
     * Extracts `event_code` from a Play install referrer string such as `event_code=FWFM2026`
     * (the server links to the Play Store with `referrer=event_code%3D<code>`).
     * Handles extra parameters and a still-encoded referrer.
     */
    fun parseReferrerEventCode(referrer: String?): String? {
        var value = referrer?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (!value.contains('=') && value.contains("%3D", ignoreCase = true)) value = decode(value)
        return value.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && decode(it[0]) == "event_code" }
            ?.let { decode(it[1]).trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: Exception) {
        value
    }
}
