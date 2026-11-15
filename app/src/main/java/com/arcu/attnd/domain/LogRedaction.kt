package com.arcu.attnd.domain

import kotlin.math.roundToLong

/**
 * Redaction for diagnostics records (logging spec §1, unit-tested in `LogRedactionTest`). Applied by
 * [AppLog] to every message and field before anything reaches logcat, the ring buffer or an upload:
 * - no search / Ask / plan text: call sites log [textFields] (the length; dev builds may add the text);
 * - no tokens, keys, push tokens, emails, device ids or support codes: sensitive field names are dropped,
 *   and emails, bearer tokens, JWTs, UUIDs and long token-like strings in any text are masked;
 * - URLs as path templates with ids replaced by `{id}` and no query string ([path]);
 * - coordinates rounded to 0.01° ([coordinate]), or only distances.
 */
object Redact {
    const val ID = "{id}"
    const val MAX_TEXT = 200
    private const val MAX_MESSAGE = 1_000

    /** Field names whose values are never logged (substring match, case-insensitive). */
    private val SENSITIVE_KEYS = listOf(
        "token", "authorization", "auth_header", "api_key", "apikey", "secret", "password", "cookie",
        "email", "device_id", "deviceid", "support_code", "integrity", "jwt", "signature", "cert",
    )

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val BEARER = Regex("""(?i)\b(bearer|token|key)([=: ]+)[A-Za-z0-9._~+/=-]{8,}""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""")
    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    /** FCM tokens, API keys and other long opaque strings (≥ 32 characters without spaces). */
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_:\-]{32,}""")

    /** A path segment that names one thing: digits, a UUID, long hex, or a code-like mixed string. */
    private val ID_SEGMENT = Regex("""^(\d+|[0-9a-fA-F-]{16,}|[A-Za-z0-9_-]*\d[A-Za-z0-9_-]*)$""")

    /**
     * `https://attnd.app/api/v2/events/5/bundle/?x=1` → `/api/v2/events/{id}/bundle/`. Keeps the API
     * version segment (`v2`); scheme, host, query and fragment are dropped.
     */
    fun path(url: String): String {
        var rest = url.substringBefore('#').substringBefore('?')
        val scheme = rest.indexOf("://")
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3)
            rest = rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) }
        }
        if (!rest.startsWith("/")) rest = "/$rest"
        return rest.split('/').joinToString("/") { segment ->
            when {
                segment.isEmpty() -> segment
                API_VERSION_SEGMENT.matches(segment) -> segment
                ID_SEGMENT.matches(segment) -> ID
                else -> segment
            }
        }
    }

    private val API_VERSION_SEGMENT = Regex("""^v\d{1,2}$""")

    /**
     * A user-typed text (search, Ask, plan request) as fields: always `<name>_len`; the text itself only
     * when [allowText] (dev builds), cut to [MAX_TEXT] and still masked.
     */
    fun textFields(name: String, text: String?, allowText: Boolean): Map<String, Any?> = buildMap {
        put("${name}_len", text?.length ?: 0)
        if (allowText && text != null) put(name, message(text.take(MAX_TEXT)))
    }

    /** A latitude or longitude rounded to 0.01° (about 1 km). */
    fun coordinate(value: Double): Double = (value * 100).roundToLong() / 100.0

    /** Masks secrets and personal identifiers in free text (messages, exception messages). */
    fun message(text: String): String {
        var out = text
        out = JWT.replace(out, "[jwt]")
        out = EMAIL.replace(out, "[email]")
        out = BEARER.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        out = UUID.replace(out, "[uuid]")
        out = LONG_TOKEN.replace(out, "[token]")
        out = URL_IN_TEXT.replace(out) { path(it.value) }
        return if (out.length > MAX_MESSAGE) out.take(MAX_MESSAGE) + "…" else out
    }

    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""")

    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        // `*_len` fields only carry a length.
        if (k.endsWith("_len")) return false
        return SENSITIVE_KEYS.any { k.contains(it) }
    }

    /** Drops sensitive keys, masks string values and turns URL-ish values into path templates. */
    fun fields(values: Map<String, Any?>): Map<String, Any?> =
        values.filterKeys { !isSensitiveKey(it) }.mapValues { (key, value) ->
            when {
                value !is String -> value
                key == "path" || key == "url" -> path(value)
                else -> message(value)
            }
        }
}
