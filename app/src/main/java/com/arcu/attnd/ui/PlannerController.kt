package com.arcu.attnd.ui

import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.PlanStore
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
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** The planner's progress and its last error (the rest of [PlanUiState] comes from [PlanStore]). */
data class PlannerProgress(val isPlanning: Boolean = false, val error: String? = null)

/**
 * The planner's actions, split out of [EventViewModel] (iOS: the planner actions on `AppState` in
 * `PlanStore.swift`): make a plan (the model's preferences when [modelAvailable], merged with the plain
 * rules), re-plan from now, swap, keep after a content change, clear. Every change goes through
 * [PlanStore.applyPlan] (the undo stack). [available] is false in screenshot mode.
 */
class PlannerController(
    private val scope: CoroutineScope,
    private val bundle: () -> EventBundle?,
    private val hearts: HeartStore,
    private val plans: PlanStore,
    private val assistant: OnDeviceAssistant,
    private val perf: PerfRecorder,
    private val available: () -> Boolean,
    private val modelAvailable: () -> Boolean,
    private val countPerf: () -> Boolean,
) {
    private val _state = MutableStateFlow(PlannerProgress())
    val state: StateFlow<PlannerProgress> = _state.asStateFlow()

    private var planJob: Job? = null

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Makes and stores a plan for [request]: the on-device model extracts preferences (JSON, validated
     * against the bundle; the plain-rule reading fills gaps or replaces it on failure), then the
     * deterministic [Planner] schedules. Returns null when there is no event or the planner is off.
     */
    suspend fun buildPlan(request: String, source: String): Plan? {
        val bundle = bundle() ?: return null
        if (!available()) return null
        AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_REQUEST, "Plan requested ($source)", mapOf("source" to source) + Redact.textFields("query", request, AppLog.allowText))
        _state.update { it.copy(isPlanning = true, error = null) }
        try {
            val now = Instant.now()
            val hearts = hearts.hearts(bundle.event.id)
            val rules = PlanRequest.heuristic(request, bundle, now)
            val modelStarted = System.nanoTime()
            val model = if (!modelAvailable()) null else try {
                val prompt = PlanRequest.prompt(bundle, request, hearts, now)
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
            val plan = timedPlan { Planner(bundle, preferences, hearts).plan(now, request.trim()) }
            if (plan.isEmpty) {
                _state.update { it.copy(error = "Nothing on the schedule fits that. Try another day or fewer limits.") }
                return plan
            }
            plans.applyPlan(bundle.event.id, plan)
            return plan
        } finally {
            _state.update { it.copy(isPlanning = false) }
        }
    }

    /** From the plan card (or an example chip). */
    fun makePlan(request: String, source: String = "card") {
        val trimmed = request.trim()
        if (trimmed.isEmpty() || planJob?.isActive == true) return
        planJob = scope.launch { buildPlan(trimmed, source) }
    }

    /** "Re-plan from now": the same preferences from the current time (also after a content change). */
    fun replanFromNow() {
        val bundle = bundle() ?: return
        val plan = plans.plan(bundle.event.id) ?: return
        val replanned = timedPlan {
            Planner(bundle, plan.preferences, hearts.hearts(bundle.event.id), plan.constraints).replan(plan, Instant.now())
        }
        if (replanned.isEmpty) {
            _state.update { it.copy(error = "Nothing left on the schedule fits this plan.") }
        } else {
            _state.update { it.copy(error = null) }
            plans.applyPlan(bundle.event.id, replanned.copy(constraints = plan.constraints, checkedOff = plan.checkedOff))
        }
    }

    fun swapPlanItem(oldId: Int, newId: Int) {
        val bundle = bundle() ?: return
        val plan = plans.plan(bundle.event.id) ?: return
        val swapped = Planner(bundle, plan.preferences, hearts.hearts(bundle.event.id)).swap(plan, oldId, newId) ?: return
        AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_SWAP, "Plan item swapped", mapOf("item_id" to oldId))
        plans.applyPlan(bundle.event.id, swapped)
    }

    /** "Keep my plan" after a content change: the changed items are accepted as they are now. */
    fun keepChangedPlan() {
        val bundle = bundle() ?: return
        val plan = plans.plan(bundle.event.id) ?: return
        val kept = plan.days.map { day -> day.copy(entries = day.entries.filter { it !is PlannedItem || bundle.scheduleItem(it.itemId) != null }) }
        val updated = plan.copy(
            days = kept,
            fingerprints = kept.flatMap { it.items }.mapNotNull { bundle.scheduleItem(it.itemId) }.associate { it.id to Planner.fingerprint(it) },
        )
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Changed plan kept", mapOf("name" to "plan_keep"))
        plans.applyPlan(bundle.event.id, updated)
    }

    fun clearPlan() {
        val eventId = bundle()?.event?.id ?: return
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Plan cleared", mapOf("name" to "plan_clear"))
        plans.applyPlan(eventId, null)
        _state.update { it.copy(error = null) }
    }

    /** One planner run: Trace section `planner` (+ `perf.signpost`), `planner.run`, API-014 `perf_planner_ms`. */
    private inline fun timedPlan(block: () -> Plan): Plan {
        val started = System.nanoTime()
        val plan = AppLog.section("planner") { block() }
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
        return plan
    }
}
