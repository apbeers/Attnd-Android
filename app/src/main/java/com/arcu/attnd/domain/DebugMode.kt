package com.arcu.attnd.domain

import java.util.Locale
import kotlin.random.Random

/**
 * Debug mode and log levels (logging spec §2–3). Production: `warning` and local only, until the user
 * turns on debug logging for [DURATION_MS] (About & support): level `debug` and upload, including the
 * [PRE_ROLL_MS] before it from the ring buffer, then back to normal; the expiry is stored, so it survives
 * restarts. Dev builds have one persistent debug code per install and are always in debug mode.
 * The debug code is random per session and never linked to the device id.
 */
object DebugMode {
    const val DURATION_MS = 10 * 60 * 1000L
    const val PRE_ROLL_MS = 5 * 60 * 1000L

    /** Crockford base32 (no I, L, O, U). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val CODE = Regex("^D-[0-9A-HJKMNP-TV-Z]{5}$")

    /** `D-7K3QX`. */
    fun newCode(random: Random = Random.Default): String =
        "D-" + (1..5).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun isValidCode(code: String?): Boolean = code != null && CODE.matches(code)

    /** The level records are kept at: dev builds and debug sessions `debug`, otherwise `warning`. */
    fun level(dev: Boolean, session: DebugSession?, nowMs: Long): LogLevel =
        if (dev || session?.isActive(nowMs) == true) LogLevel.DEBUG else LogLevel.WARNING

    /** Uploads happen only in dev builds or while a debug session runs. */
    fun uploads(dev: Boolean, session: DebugSession?, nowMs: Long): Boolean = dev || session?.isActive(nowMs) == true

    /** `reason` of an upload. */
    fun reason(dev: Boolean): String = if (dev) "dev" else "debug_mode"
}

/** One production debug session (or, with [expiresAtMs] = [Long.MAX_VALUE], a dev build's permanent one). */
data class DebugSession(val code: String, val startedAtMs: Long, val expiresAtMs: Long) {
    fun isActive(nowMs: Long): Boolean = nowMs in startedAtMs until expiresAtMs

    /** Records from here on are uploaded: the session start minus the pre-roll (dev: everything). */
    val uploadFromMs: Long get() = if (expiresAtMs == Long.MAX_VALUE) 0L else startedAtMs - DebugMode.PRE_ROLL_MS

    /** Countdown `9:41` (minutes:seconds, rounded up), "0:00" once over. */
    fun remainingText(nowMs: Long): String {
        val left = ((expiresAtMs - nowMs).coerceAtLeast(0) + 999) / 1000
        return String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
    }

    companion object {
        fun start(code: String, nowMs: Long) = DebugSession(code, nowMs, nowMs + DebugMode.DURATION_MS)

        fun permanent(code: String) = DebugSession(code, 0L, Long.MAX_VALUE)

        /** Stored as `code|start|expiry`; null when unreadable. */
        fun decode(text: String?): DebugSession? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 3 || !DebugMode.isValidCode(parts[0])) return null
            val start = parts[1].toLongOrNull() ?: return null
            val end = parts[2].toLongOrNull() ?: return null
            return if (end > start) DebugSession(parts[0], start, end) else null
        }
    }

    fun encode(): String = "$code|$startedAtMs|$expiresAtMs"
}
