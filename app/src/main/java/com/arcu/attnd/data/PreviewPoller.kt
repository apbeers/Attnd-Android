package com.arcu.attnd.data

import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.PreviewPoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * API-026: while this phone is the shown event's preview phone, holds `changes/` so the organizer's
 * edits appear at once ([PreviewPoll.next]: changed / newer → refetch now, else wait `retry_after`,
 * errors 15 s, `preview: false` → stop). [run] is called only while the activity is resumed (and never
 * in screenshot mode); its cancellation stops the poll.
 */
class PreviewPoller(private val repository: EventRepository) {

    suspend fun run() {
        combine(repository.previewEventId, repository.bundle) { preview, bundle -> preview?.takeIf { it == bundle?.event?.id } }
            .distinctUntilChanged()
            .collectLatest { eventId -> if (eventId != null) poll(eventId) }
    }

    private suspend fun poll(eventId: Int) {
        while (true) {
            val since = repository.bundle.value?.contentVersion
            val answer = try {
                repository.changes(eventId, since)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.NET, "preview_changes", e, LogLevel.WARNING)
                delay(PreviewPoll.ERROR_WAIT_MS)
                continue
            }
            when (val next = PreviewPoll.next(answer, since)) {
                PreviewPoll.Next.Refresh -> {
                    // At once, no random wait. A refresh that didn't move the version (offline) waits first.
                    repository.refresh(silent = true, source = "preview")
                    if (repository.bundle.value?.contentVersion == since) delay(PreviewPoll.ERROR_WAIT_MS)
                }
                is PreviewPoll.Next.Wait -> if (next.seconds > 0) delay(next.seconds * 1_000L)
                PreviewPoll.Next.Stop -> {
                    repository.endPreview(eventId)
                    return
                }
            }
        }
    }
}
