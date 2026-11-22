package com.arcu.attnd.ui

import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.ScheduleController
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.PerfMetric
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanRequest
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScheduleProposal
import com.arcu.attnd.domain.propose
import com.arcu.attnd.domain.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** The planner's progress and its last error (the rest of [PlanUiState] comes from My schedule). */
data class PlannerProgress(val isPlanning: Boolean = false, val error: String? = null)

/**
 * The planner's actions, split out of [EventViewModel] (iOS: the planner actions on `AppState` in
 * `PlanStore.swift`). One list (Nov 22, 2026): the planner is a function. [buildPlan] reads a request (the
 * model's preferences when [modelAvailable], merged with the plain rules) and plans with My schedule's
 * items as the must-includes ([Planner.Companion.propose]); nothing is applied here: the assistant previews
 * the result as a proposal and Apply turns it into My schedule edits ([ScheduleController.applyPlan]).
 * [available] is false in screenshot mode.
 */
class PlannerController(
    private val bundle: () -> EventBundle?,
    private val schedule: ScheduleController,
    private val assistant: OnDeviceAssistant,
    private val perf: PerfRecorder,
    private val available: () -> Boolean,
    private val modelAvailable: () -> Boolean,
    private val countPerf: () -> Boolean,
) {
    private val _state = MutableStateFlow(PlannerProgress())
    val state: StateFlow<PlannerProgress> = _state.asStateFlow()

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Plans for [request]: the on-device model extracts preferences (JSON, validated against the bundle;
     * the plain-rule reading fills gaps or replaces it on failure), then the deterministic [Planner]
     * schedules from now with My schedule's items as must-includes. Returns the plan to preview (nothing
     * is applied; empty: nothing fits or everything is already in My schedule, with [PlannerProgress.error]),
     * or null when there is no event or the planner is off.
     */
    suspend fun buildPlan(request: String, source: String): Plan? {
        val bundle = bundle() ?: return null
        if (!available()) return null
        val eventId = bundle.event.id
        AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_REQUEST, "Plan requested ($source)", mapOf("source" to source) + Redact.textFields("query", request, AppLog.allowText))
        _state.update { it.copy(isPlanning = true, error = null) }
        try {
            val now = Instant.now()
            val mine = schedule.schedule(eventId)
            val rules = PlanRequest.heuristic(request, bundle, now)
            val modelStarted = System.nanoTime()
            val model = if (!modelAvailable()) null else try {
                val prompt = PlanRequest.prompt(bundle, request, mine.hearts, now)
                val raw = assistant.extractPreferences(prompt)
                PlanRequest.parse(raw, bundle, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.PLANNER, "plan.preferences", e, LogLevel.WARNING)
                null
            }
            val preferences = PlanRequest.merge(model, rules)
            AppLog.log(
                if (model != null) LogLevel.INFO else LogLevel.WARNING, LogCategory.PLANNER, LogEvent.PLANNER_EXTRACT,
                if (model != null) "Preferences from Gemini Nano" else "Preferences from the plain rules only",
                mapOf(
                    "ms" to (System.nanoTime() - modelStarted) / 1_000_000, "ok" to (model != null),
                    "model" to if (model != null) "gemini_nano" else "rules",
                    "liked_tags" to preferences.likedTags.size, "disliked_tags" to preferences.dislikedTags.size, "days" to preferences.days.size,
                ),
            )
            val (plan, proposal) = timedPlan {
                Planner.propose(mine, schedule.preferences(eventId), bundle, now, request = preferences, text = request.trim())
            }
            when {
                plan.isEmpty -> _state.update { it.copy(error = NOTHING_FITS) }
                proposal.isEmpty -> _state.update { it.copy(error = ALREADY_IN_SCHEDULE) }
            }
            return if (proposal.isEmpty) plan.copy(days = emptyList(), fingerprints = emptyMap()) else plan
        } finally {
            _state.update { it.copy(isPlanning = false) }
        }
    }

    /** One planner run: Trace section `planner` (+ `perf.signpost`), `planner.run`, API-014 `perf_planner_ms`. */
    private inline fun timedPlan(block: () -> Pair<Plan, ScheduleProposal>): Pair<Plan, ScheduleProposal> {
        val started = System.nanoTime()
        val result = AppLog.section("planner") { block() }
        val plan = result.first
        val ms = (System.nanoTime() - started) / 1_000_000
        AppLog.info(
            LogCategory.PLANNER, LogEvent.PLANNER_RUN, "Plan: ${plan.itemIds.size} items in $ms ms",
            mapOf(
                "ms" to ms, "items" to plan.itemIds.size, "days" to plan.days.size,
                "meals" to plan.days.sumOf { day -> day.entries.count { it is MealBreak } },
                "pace" to plan.preferences.pace,
            ),
        )
        if (countPerf()) perf.record(PerfMetric.PLANNER_MS, PerfMetric.plannerBucket(ms))
        return result
    }

    companion object {
        const val NOTHING_FITS = "Nothing on the schedule fits that. Try another day or fewer limits."
        const val ALREADY_IN_SCHEDULE = "Everything that fits is already in your schedule."
    }
}
