package com.arcu.attnd.domain

/**
 * API-026 preview phones: the organizer's "Preview on my phone" QR is the join link with
 * `?preview=<token>`. The token goes once with `join/`; `preview: true` in the answer makes this the
 * event's preview phone, which then long-polls `GET events/<id>/changes/?since=` while the app is on
 * screen. Shared rules with iOS (`DeepLink.previewToken`, `PreviewPoll`); tested in `PreviewPhoneTest`.
 */
object PreviewPoll {

    /** After a failed `changes/` call, or a refresh that didn't move the version. */
    const val ERROR_WAIT_MS = 15_000L

    /** `retry_after` is clamped to this (seconds). */
    const val MAX_RETRY_AFTER_S = 3_600

    /** The `changes/` answer: missing keys default (`changed` false, `preview` true, `retry_after` 0). */
    data class Answer(
        val contentVersion: Int? = null,
        val changed: Boolean = false,
        val preview: Boolean = true,
        val retryAfterSeconds: Int = 0,
    ) {
        companion object {
            fun of(contentVersion: Int?, changed: Boolean?, preview: Boolean?, retryAfter: Int?): Answer = Answer(
                contentVersion = contentVersion,
                changed = changed ?: false,
                preview = preview ?: true,
                retryAfterSeconds = (retryAfter ?: 0).coerceIn(0, MAX_RETRY_AFTER_S),
            )
        }
    }

    sealed interface Next {
        /** Refetch the bundle now (no random wait), then poll again from the new version. */
        data object Refresh : Next

        /** Call again after this many seconds (0 = at once). */
        data class Wait(val seconds: Int) : Next

        /** Not a preview phone (any more): back to the normal polling. */
        data object Stop : Next
    }

    /** The next step after [answer] to a call made with [since] (the bundle's content version, null if none). */
    fun next(answer: Answer, since: Int?): Next {
        if (!answer.preview) return Next.Stop
        val newer = answer.contentVersion?.let { v -> since == null || v > since } ?: false
        if (answer.changed || newer) return Next.Refresh
        return Next.Wait(answer.retryAfterSeconds)
    }

    /**
     * Whether the long-poll runs: the activity is resumed, this phone is the preview phone of the
     * event that is shown, and it isn't store-screenshot mode.
     */
    fun runs(resumed: Boolean, previewEventId: Int?, shownEventId: Int?, screenshot: Boolean): Boolean =
        resumed && !screenshot && previewEventId != null && previewEventId == shownEventId

    /** The green "Instant updates on" capsule: this is the shown event's preview phone (not in screenshot mode). */
    fun showsCapsule(previewEventId: Int?, shownEventId: Int?, screenshot: Boolean): Boolean =
        !screenshot && previewEventId != null && previewEventId == shownEventId

    /** What the join answer's `preview` key does to the stored preview event: true sets it, false ends it, missing keeps it. */
    fun afterJoin(stored: Int?, eventId: Int, preview: Boolean?): Int? = when (preview) {
        true -> eventId
        false -> stored?.takeIf { it != eventId }
        null -> stored
    }
}
