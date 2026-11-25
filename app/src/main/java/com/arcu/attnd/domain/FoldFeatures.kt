package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Which part of the width the Undo toast may use (iOS `UndoToastPane`): one pane of an unfolded foldable (the one
 * the action was tapped in, else the screen's primary pane), never across the fold. [fold] = the reported vertical
 * hinge (container coordinates); [windowMidX] = the middle of a wide window without one. Returns (start, end).
 */
object UndoToastPane {
    fun span(
        containerWidth: Double,
        fold: ClosedFloatingPointRange<Double>?,
        windowMidX: Double?,
        touchX: Double?,
        primaryIsTrailing: Boolean,
    ): ClosedFloatingPointRange<Double> {
        val full = 0.0..maxOf(containerWidth, 0.0)
        val split = fold ?: windowMidX?.let { it..it } ?: return full
        if (split.start <= 120 || split.endInclusive >= containerWidth - 120) return full
        val leading = 0.0..split.start
        val trailing = split.endInclusive..containerWidth
        if (touchX != null) {
            if (touchX < split.start) return leading
            if (touchX > split.endInclusive) return trailing
        }
        return if (primaryIsTrailing) trailing else leading
    }
}

/**
 * What the tabletop glance shows (iOS `TabletopGlance`): Now and Next, from My schedule first ([mine]), else the
 * event's own. Mine: the one that started last; the event's: the earliest started.
 */
data class TabletopGlance(
    val now: ScheduleItem?,
    val next: ScheduleItem?,
    val nowIsMine: Boolean = false,
    val nextIsMine: Boolean = false,
) {
    companion object {
        private val BY_START = compareBy<ScheduleItem>({ it.startTime?.toInstant() }, { it.id })

        fun of(schedule: List<ScheduleItem>, mine: Set<Int>, now: Instant): TabletopGlance {
            val timed = schedule.filter { it.startTime != null }
            val onNow = timed.filter { ScheduleGrouping.isHappeningNow(it, now) }.sortedWith(BY_START)
            val upcoming = timed.filter { it.startTime!!.toInstant().isAfter(now) }.sortedWith(BY_START)
            val myNow = onNow.lastOrNull { it.id in mine }
            val myNext = upcoming.firstOrNull { it.id in mine }
            return TabletopGlance(
                now = myNow ?: onNow.firstOrNull(),
                next = myNext ?: upcoming.firstOrNull(),
                nowIsMine = myNow != null,
                nextIsMine = myNext != null,
            )
        }

        /** Whether the event is on (between its start and end, else while a set is on or starts within 2 h). */
        fun isLive(eventStart: Instant?, eventEnd: Instant?, schedule: List<ScheduleItem>, now: Instant): Boolean {
            if (eventStart != null && eventEnd != null && eventEnd.isAfter(eventStart)) {
                return !eventStart.isAfter(now) && now.isBefore(eventEnd)
            }
            val soon = now.plus(Duration.ofHours(2))
            return schedule.any { item ->
                val start = item.startTime?.toInstant()
                ScheduleGrouping.isHappeningNow(item, now) || (start != null && start.isAfter(now) && !start.isAfter(soon))
            }
        }

        /** The glance's live countdown to Next (iOS `Text(timerInterval:)`): "1:05:09", "12:05", "0:09"; never negative. */
        fun countdown(from: Instant, to: Instant): String {
            val total = maxOf(Duration.between(from, to).seconds, 0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }

        /** The At the Event mode is offered once (phase 10b draws the offer). */
        fun shouldOffer(live: Boolean, alreadyOffered: Boolean, modeOn: Boolean): Boolean = live && !alreadyOffered && !modeOn
    }
}
