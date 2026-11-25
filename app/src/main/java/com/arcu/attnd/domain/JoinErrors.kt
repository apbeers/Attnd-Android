package com.arcu.attnd.domain

/**
 * What the launch screen says when joining fails with an answer from the server. API-025: an unpaid event's free
 * limit answers `409 {"error": "event_full_free", "message": …}`; the message is written for attendees, so it is shown
 * as is (trimmed, ≤ 300 characters). Null = the app's generic text for that status.
 */
object JoinErrors {
    const val FULL_FALLBACK = "This event is full for now. Ask the organizer, then try again."

    fun message(code: Int, error: String?, serverMessage: String?): String? = when {
        code == 409 && error == "event_full_free" ->
            serverMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(300) ?: FULL_FALLBACK
        else -> null
    }
}
