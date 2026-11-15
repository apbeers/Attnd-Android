package com.arcu.attnd.ui.assistant

import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.domain.ActionContext
import com.arcu.attnd.domain.ActionDispatcher
import com.arcu.attnd.domain.ActionExecutor
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionOutcome
import com.arcu.attnd.domain.ActionRegistry
import com.arcu.attnd.domain.ActionResultType
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.AssistantFlow
import com.arcu.attnd.domain.AssistantIntent
import com.arcu.attnd.domain.AssistantModel
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.AssistantTurn
import com.arcu.attnd.domain.BlockValidator
import com.arcu.attnd.domain.CalendarEntries
import com.arcu.attnd.domain.CalendarEntry
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.ConfirmKind
import com.arcu.attnd.domain.ConversationTurn
import com.arcu.attnd.domain.Dispatch
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.LiveHintRules
import com.arcu.attnd.domain.LiveHints
import com.arcu.attnd.domain.LocationSample
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanEdit
import com.arcu.attnd.domain.PlanEditor
import com.arcu.attnd.domain.PlanOp
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.PlanVersions
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.Redact
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SharedPayload
import com.arcu.attnd.domain.SharedPlans
import com.arcu.attnd.domain.StoredTurn
import com.arcu.attnd.domain.TurnPath
import com.arcu.attnd.domain.TurnSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** What the platform gives the assistant (implemented by EventViewModel). */
interface AssistantHost {
    /** Gemini Nano is AVAILABLE and this isn't screenshot mode: the model path and the planner. */
    fun modelAvailable(): Boolean

    /** Likes / unlikes with the API-009 count; true when it changed. */
    fun setLiked(kind: SearchKind, id: Int, liked: Boolean): Boolean

    /** Makes and stores a plan (the model reads the request; plain rules fill in); null on failure. */
    suspend fun makePlan(request: String, source: String): Plan?

    /** The last planner error text, if any. */
    fun planError(): String?

    /** `open(screen, id?)`: navigates to a manual screen (closing the assistant). */
    fun open(screen: String, ref: EntityRef?)

    /** A setting toggle; false when turning it on needs POST_NOTIFICATIONS first. */
    fun setSetting(name: ActionName, on: Boolean): Boolean

    fun settingOn(name: ActionName): Boolean
    fun userLocation(): GeoPoint?
    fun units(): DistanceUnits
}

/** A one-shot request the screen carries out with an Activity (share sheet, calendar, maps, permission). */
sealed interface AssistantEffect {
    data class Share(val text: String, val title: String) : AssistantEffect
    data class Calendar(val entry: CalendarEntry) : AssistantEffect
    data class Directions(val point: GeoPoint) : AssistantEffect
    data class RequestNotifications(val action: AssistantAction) : AssistantEffect
}

/** A shared plan link's offer under its turn: "Use this plan" / "Merge with mine". */
data class SharedOffer(val ids: List<Int>, val day: LocalDate?, val handled: Boolean = false)

data class AssistantTurnUi(
    val id: Long,
    /** What the user typed or the chip they tapped; null for a turn the app added. */
    val input: String?,
    val blocks: List<AssistantBlock>,
    val source: TurnSource = TurnSource.RULES,
    /** A write applied with Undo (plan change, like, check-off): Undo / Apply act on it. */
    val applied: AssistantAction? = null,
    val undone: Boolean = false,
    /** Indexes of `confirm` blocks already answered. */
    val answered: Set<Int> = emptySet(),
    /** The model is still writing this turn (blocks appear as they complete). */
    val streaming: Boolean = false,
    val shared: SharedOffer? = null,
)

data class AssistantState(
    val eventId: Int? = null,
    val turns: List<AssistantTurnUi> = emptyList(),
    /** The path of the turn being worked on (busy indicator). */
    val busy: TurnPath? = null,
    /** The input bar's chosen path (null = automatic). */
    val forced: TurnPath? = null,
    val live: LiveHints = LiveHints(),
    /** The plan has a change to undo. */
    val canUndo: Boolean = false,
    /** The plan's calendar chooser (one Add per item). */
    val calendar: List<CalendarEntry>? = null,
    /** A plan item is on now or soon today: sample the position while the app is visible. */
    val wantsLocation: Boolean = false,
    /** Incremented when a turn is added (the screen scrolls to it). */
    val serial: Int = 0,
)

/**
 * The event assistant on Android (assistant spec §1–8): one conversation per event; every turn goes
 * through the rule-based [AssistantRouter] or, when Gemini Nano is available and [AssistantFlow.path]
 * says so, the model ([AssistantModel], streamed, router fallback). Writes go through
 * [ActionDispatcher] (validation, confirm blocks, Undo) to this [ActionExecutor]; plan changes are
 * stored with their undo stack ([PlanStore.applyPlan]). Live hints (running late, check-off, gaps,
 * skipped, recap) come from position samples taken only while the app is visible.
 */
class AssistantController(
    private val scope: CoroutineScope,
    private val repository: EventRepository,
    private val hearts: HeartStore,
    private val plans: PlanStore,
    private val model: OnDeviceAssistant,
    private val host: AssistantHost,
) : ActionExecutor {

    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state

    private val _effects = MutableSharedFlow<AssistantEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<AssistantEffect> = _effects

    private val dispatcher = ActionDispatcher(this)
    private var nextId = 1L
    private var modelJob: Job? = null
    private val samples = ArrayDeque<LocationSample>()
    private val dismissedSkips = HashSet<Int>()
    private var lateLoggedFor: Int? = null

    /** The source of the write being executed (for `plan.edit`'s `source`). */
    private var quietSource: ActionSource? = null

    init {
        scope.launch {
            repository.bundle.map { it?.event?.id }.distinctUntilChanged().collect { load(it) }
        }
    }

    // ── Conversation ────────────────────────────────────────────────────────

    private fun load(eventId: Int?) {
        modelJob?.cancel()
        samples.clear()
        dismissedSkips.clear()
        val bundle = repository.bundle.value
        val turns = if (eventId == null || bundle == null) emptyList() else {
            val ctx = context()
            plans.transcript(eventId).map { stored ->
                // Stored turns are shown again, re-validated; old confirmations can't be answered.
                val blocks = ctx?.let { BlockValidator.validate(stored.turn.blocks, it.blockContext) } ?: stored.turn.blocks
                AssistantTurnUi(nextId++, stored.input, blocks.filter { it !is AssistantBlock.Confirm })
            }
        }
        _state.value = AssistantState(eventId = eventId, turns = turns, canUndo = eventId?.let { plans.history(it).canUndo } == true)
        refreshLive()
    }

    fun clearConversation() {
        val eventId = _state.value.eventId ?: return
        modelJob?.cancel()
        plans.setTranscript(eventId, emptyList())
        plans.setConversation(eventId, AssistantConversation())
        _state.update { it.copy(turns = emptyList(), busy = null) }
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Assistant conversation cleared", mapOf("name" to "assistant_clear"))
    }

    fun setForced(path: TurnPath?) = _state.update { it.copy(forced = path) }

    fun context(now: Instant = Instant.now()): AssistantContext? {
        val bundle = repository.bundle.value ?: return null
        val eventId = bundle.event.id
        val available = host.modelAvailable()
        return AssistantContext(
            bundle = bundle,
            hearts = hearts.hearts(eventId),
            plan = plans.plan(eventId)?.takeIf { !it.isEmpty },
            now = now,
            user = host.userLocation(),
            canPlan = available,
            modelAvailable = available,
            history = plans.history(eventId),
            units = host.units(),
            locale = Locale.getDefault(),
        )
    }

    /** The input bar's intent chip for [text] (debounced by the screen). */
    fun predict(text: String): TurnPath {
        val ctx = context() ?: return TurnPath.SEARCH
        if (text.isBlank()) return _state.value.forced ?: TurnPath.SEARCH
        val routed = AssistantRouter.route(text, ctx)
        return AssistantFlow.path(routed.intent, text, ctx.modelAvailable, _state.value.forced)
    }

    /** Enter / send: runs the full turn. */
    fun submit(input: String, forced: TurnPath? = _state.value.forced) {
        val text = input.trim()
        val ctx = context() ?: return
        if (text.isEmpty()) {
            finish(null, AssistantRouter.route("", ctx).turn, TurnSource.RULES, AssistantIntent.EMPTY, System.nanoTime(), ctx)
            return
        }
        val started = System.nanoTime()
        val routed = AssistantRouter.route(text, ctx)
        when (AssistantFlow.path(routed.intent, text, ctx.modelAvailable, forced)) {
            TurnPath.SEARCH ->
                if (forced == TurnPath.SEARCH) finish(text, AssistantFlow.searchTurn(text, ctx), TurnSource.RULES, AssistantIntent.SEARCH, started, ctx)
                else finish(text, routed.turn, TurnSource.RULES, routed.intent, started, ctx)
            TurnPath.PLAN -> finish(text, AssistantFlow.planTurn(text, routed, ctx), TurnSource.RULES, routed.intent, started, ctx)
            TurnPath.ASK -> ask(text, ctx, started)
        }
    }

    /** The model path: streamed blocks, then the validated turn (or the router's on invalid output). */
    private fun ask(text: String, ctx: AssistantContext, started: Long) {
        modelJob?.cancel()
        val eventId = ctx.bundle.event.id
        val id = nextId++
        _state.update {
            it.copy(turns = it.turns + AssistantTurnUi(id, text, emptyList(), TurnSource.MODEL, streaming = true), busy = TurnPath.ASK, serial = it.serial + 1)
        }
        modelJob = scope.launch {
            var raw: String? = null
            try {
                val retrieved = AssistantModel.retrieve(text, ctx)
                val prompt = AssistantModel.prompt(text, ctx, plans.conversation(eventId), retrieved)
                model.turn(prompt).collect { partial ->
                    raw = partial
                    val shown = AssistantModel.completeBlocks(partial, ctx).filter { it !is AssistantBlock.Chips }
                    _state.update { s -> s.copy(turns = s.turns.map { t -> if (t.id == id) t.copy(blocks = shown) else t }) }
                }
            } catch (e: CancellationException) {
                _state.update { s -> s.copy(turns = s.turns.filter { it.id != id }, busy = null) }
                throw e
            } catch (e: Exception) {
                AppLog.warning(
                    LogCategory.ASSISTANT, LogEvent.ASSISTANT_ERROR, "Assistant turn failed (${e.javaClass.simpleName})",
                    mapOf("kind" to "turn", "error" to AppLog.errorKind(e), "domain" to e.javaClass.simpleName),
                )
                raw = null
            }
            val resolved = AssistantModel.resolve(text, raw, ctx)
            _state.update { s -> s.copy(turns = s.turns.filter { it.id != id }) }
            finish(
                text, resolved.turn, resolved.source, resolved.routed?.intent ?: AssistantIntent.QUESTION, started, ctx,
                fallback = resolved.source == TurnSource.RULES, modelTried = true,
            )
        }
    }

    /** Adds a finished turn, runs its actions, remembers it and logs it. */
    private fun finish(
        input: String?,
        turn: AssistantTurn,
        source: TurnSource,
        intent: AssistantIntent,
        started: Long,
        ctx: AssistantContext,
        fallback: Boolean = false,
        modelTried: Boolean = false,
    ) {
        val actionSource = if (source == TurnSource.MODEL) ActionSource.MODEL else ActionSource.RULES
        var blocks = turn.blocks
        var applied: AssistantAction? = null
        for (action in turn.actions) {
            val result = runQuietly(action, actionSource)
            if (result.applied != null) applied = result.applied
            // The router's blocks already show the result; the model's may not: add what it lacks.
            if (source == TurnSource.MODEL) {
                val types = blocks.map { it.type }.toSet()
                blocks = blocks + result.blocks.filter { it.type !in types }
            }
            if (result.blocks.isEmpty() && result.message != null && source == TurnSource.MODEL) blocks = blocks + AssistantBlock.Text(result.message)
        }
        val validated = context()?.let { BlockValidator.validate(blocks, it.blockContext) } ?: blocks
        append(input, validated, source, applied)
        val ms = (System.nanoTime() - started) / 1_000_000
        AppLog.info(
            LogCategory.ASSISTANT, LogEvent.ASSISTANT_TURN, "Assistant turn (${if (source == TurnSource.MODEL) "model" else "rules"})",
            mapOf(
                "path" to if (source == TurnSource.MODEL) "model" else "rules",
                "intent" to intent.name.lowercase(),
                "ms" to ms,
                "blocks" to validated.size,
                "fallback" to (modelTried && fallback),
            ) + (input?.let { Redact.textFields("query", it, AppLog.allowText) } ?: emptyMap()),
        )
        remember(ctx.bundle.event.id, input, AssistantTurn(validated))
    }

    private fun append(input: String?, blocks: List<AssistantBlock>, source: TurnSource = TurnSource.RULES, applied: AssistantAction? = null, shared: SharedOffer? = null) {
        val eventId = _state.value.eventId
        if (blocks.isEmpty() && shared == null) {
            _state.update { it.copy(busy = null) }
            return
        }
        blocks.forEach { block ->
            AppLog.debug(LogCategory.ASSISTANT, LogEvent.ASSISTANT_BLOCK, "Block ${block.type}", mapOf("type" to block.type, "ids" to AssistantFlow.refs(block).size))
        }
        _state.update {
            it.copy(
                turns = it.turns + AssistantTurnUi(nextId++, input, blocks, source, applied = applied, shared = shared),
                busy = null,
                serial = it.serial + 1,
                canUndo = eventId?.let { id -> plans.history(id).canUndo } == true,
            )
        }
        persist()
    }

    private fun persist() {
        val eventId = _state.value.eventId ?: return
        plans.setTranscript(eventId, _state.value.turns.filter { !it.streaming }.map { StoredTurn(it.input, AssistantTurn(it.blocks)) })
    }

    /** The model's conversation: the user's text and what the assistant answered (text only). */
    private fun remember(eventId: Int, input: String?, turn: AssistantTurn) {
        val bundle = repository.bundle.value ?: return
        var conversation = plans.conversation(eventId)
        if (input != null) conversation = conversation.with(ConversationTurn(true, input))
        conversation = conversation.with(ConversationTurn(false, AssistantFlow.replyText(turn, bundle)))
        plans.setConversation(eventId, conversation)
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private class Quiet(val blocks: List<AssistantBlock>, val message: String?, val applied: AssistantAction?)

    /** A turn's own action: dispatched with its source; the result is returned, not shown as a new turn. */
    private fun runQuietly(action: AssistantAction, source: ActionSource): Quiet {
        val ctx = context() ?: return Quiet(emptyList(), null, null)
        if (!action.name.mutates && action.name.result != ActionResultType.NAVIGATION) {
            return Quiet(AssistantRouter.renderRead(action, ctx), null, null)
        }
        quietSource = source
        try {
            return when (val d = dispatcher.dispatch(action, actionContext(ctx), source)) {
                is Dispatch.Rejected -> {
                    logAction(action, "failed")
                    Quiet(emptyList(), rejectText(d.reason), null)
                }
                is Dispatch.NeedsConfirmation -> Quiet(listOf(d.confirm), null, null)
                is Dispatch.Executed -> when (val outcome = d.outcome) {
                    is ActionOutcome.Done -> {
                        logAction(action, "applied")
                        Quiet(outcome.blocks, outcome.message, if (d.undo != null) action else null)
                    }
                    is ActionOutcome.Failed -> {
                        logAction(action, "failed")
                        Quiet(emptyList(), outcome.reason, null)
                    }
                }
            }
        } finally {
            quietSource = null
        }
    }

    /**
     * A chip, a card button, the pinned plan or a Live hint: runs [action] and shows its result as a
     * new turn labelled [label]. Reads render with the router's layout; writes go through the
     * dispatcher ([source] RULES confirms CHIP actions first; MANUAL is the user's own button).
     * Returns the outcome's message (for a snackbar when the assistant isn't open).
     */
    fun run(action: AssistantAction, label: String?, source: ActionSource = ActionSource.RULES, confirmed: Boolean = false): String? {
        val ctx = context() ?: return null
        if (!action.name.mutates && action.name.result != ActionResultType.NAVIGATION) {
            append(label, AssistantRouter.renderRead(action, ctx))
            logAction(action, "applied")
            remember(ctx.bundle.event.id, label, AssistantTurn(_state.value.turns.lastOrNull()?.blocks.orEmpty()))
            return null
        }
        quietSource = source
        val dispatched = try {
            dispatcher.dispatch(action, actionContext(ctx), source, confirmed)
        } finally {
            quietSource = null
        }
        return when (val d = dispatched) {
            is Dispatch.Rejected -> {
                logAction(action, "failed")
                rejectText(d.reason).also { append(label, listOf(AssistantBlock.Text(it))) }
            }
            is Dispatch.NeedsConfirmation -> {
                append(label, listOf(d.confirm))
                null
            }
            is Dispatch.Executed -> when (val outcome = d.outcome) {
                is ActionOutcome.Done -> {
                    logAction(action, if (confirmed) "confirmed" else "applied", if (confirmed) ConfirmKind.CHIP else action.name.confirm)
                    val blocks = listOfNotNull(outcome.message?.let { AssistantBlock.Text(it) }) + outcome.blocks
                    val validated = BlockValidator.validate(blocks, context()?.blockContext ?: ctx.blockContext)
                    append(label, validated, applied = if (d.undo != null) action else null)
                    if (validated.isNotEmpty()) remember(ctx.bundle.event.id, label, AssistantTurn(validated))
                    outcome.message
                }
                is ActionOutcome.Failed -> {
                    logAction(action, "failed")
                    append(label, listOf(AssistantBlock.Text(outcome.reason)))
                    outcome.reason
                }
            }
        }
    }

    fun chip(chip: Chip) {
        run(chip.action, chip.label)
    }

    /** A `confirm` block's Yes / No. */
    fun answer(turnId: Long, index: Int, yes: Boolean) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        val confirm = turn.blocks.getOrNull(index) as? AssistantBlock.Confirm ?: return
        if (index in turn.answered) return
        _state.update { s -> s.copy(turns = s.turns.map { if (it.id == turnId) it.copy(answered = it.answered + index) else it }) }
        if (yes) {
            run(confirm.action, null, ActionSource.RULES, confirmed = true)
        } else {
            logAction(confirm.action, "declined", ConfirmKind.CHIP)
            append(null, listOf(AssistantBlock.Text("OK, nothing changed.")))
        }
    }

    /** A turn's Undo: the applied write's inverse ([ActionRegistry.undoFor]). */
    fun undo(turnId: Long) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        val applied = turn.applied ?: return
        if (turn.undone) return
        val inverse = ActionRegistry.undoFor(applied) ?: return
        val ctx = context() ?: return
        val result = dispatcher.dispatch(inverse, actionContext(ctx), ActionSource.MANUAL)
        if (result is Dispatch.Executed && result.outcome is ActionOutcome.Done) {
            logAction(applied, "undone")
            _state.update { s -> s.copy(turns = s.turns.map { if (it.id == turnId) it.copy(undone = true) else it }, canUndo = plans.history(ctx.bundle.event.id).canUndo) }
        }
    }

    /** Apply again after an Undo. */
    fun reapply(turnId: Long) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        val applied = turn.applied ?: return
        if (!turn.undone) return
        val ctx = context() ?: return
        val result = dispatcher.dispatch(applied, actionContext(ctx), ActionSource.MANUAL)
        if (result is Dispatch.Executed && result.outcome is ActionOutcome.Done) {
            logAction(applied, "applied")
            _state.update { s -> s.copy(turns = s.turns.map { if (it.id == turnId) it.copy(undone = false) else it }, canUndo = plans.history(ctx.bundle.event.id).canUndo) }
        }
    }

    private fun actionContext(ctx: AssistantContext) = ActionContext(ctx.bundle, ctx.now, hasLocation = ctx.user != null, hasPlan = ctx.plan != null)

    private fun rejectText(reason: String): String = when (reason) {
        "no plan" -> AssistantRouter.NO_PLAN_TEXT
        "needs location" -> "Turn on location to see what's closest."
        else -> "I can't do that here."
    }

    private fun logAction(action: AssistantAction, result: String, confirm: ConfirmKind = action.name.confirm) {
        AppLog.info(
            LogCategory.ASSISTANT, LogEvent.ASSISTANT_ACTION, "Assistant action ${action.name.key}: $result",
            mapOf("name" to action.name.key, "result" to result, "confirm" to confirm.name.lowercase()),
        )
    }

    /** The platform executor: only validated, confirmed actions arrive here ([ActionDispatcher]). */
    override fun execute(action: AssistantAction): ActionOutcome {
        val bundle = repository.bundle.value ?: return ActionOutcome.Failed("No event.")
        val eventId = bundle.event.id
        val now = Instant.now()
        fun plan() = plans.plan(eventId)?.takeIf { !it.isEmpty }
        return when (action.name) {
            ActionName.LIKE, ActionName.UNLIKE -> {
                val (kind, id) = action.likeTarget() ?: return ActionOutcome.Failed("I couldn't find that.")
                val liked = action.name == ActionName.LIKE
                host.setLiked(kind, id, liked)
                val ref = EntityRef(if (kind == SearchKind.SCHEDULE) RefKind.SCHEDULE else RefKind.LOCATION, id)
                val name = ref.name(bundle) ?: "it"
                val clash = if (liked && kind == SearchKind.SCHEDULE) plan()?.let { LiveAssist.likeClash(id, it, bundle) } else null
                ActionOutcome.Done(
                    if (liked) "Liked $name." else "Unliked $name.",
                    listOf(BlockValidator.card(ref)) + listOfNotNull(clash?.let { AssistantBlock.Chips(listOf(it)) }),
                )
            }
            ActionName.PLAN_EDIT -> {
                val plan = plan() ?: return ActionOutcome.Failed(AssistantRouter.NO_PLAN_TEXT)
                val ops = action.ops(bundle, now) ?: return ActionOutcome.Failed("I couldn't read that change.")
                applyEdit(eventId, PlanEditor.preview(plan, ops, bundle, hearts.hearts(eventId), now), ops.map(::opName))
            }
            ActionName.PLAN_VERSION -> {
                val plan = plan() ?: return ActionOutcome.Failed(AssistantRouter.NO_PLAN_TEXT)
                val kind = PlanVersionKind.of(action.string("kind")) ?: return ActionOutcome.Failed("I don't know that version.")
                applyEdit(eventId, PlanVersions.make(kind, plan, bundle, hearts.hearts(eventId), now), listOf("constraint"), prefix = "${kind.label}: ")
            }
            ActionName.PLAN_UNDO -> {
                val restored = plans.undoPlan(eventId)
                val depth = plans.history(eventId).plans.size
                AppLog.info(LogCategory.PLANNER, LogEvent.PLAN_UNDO, "Plan undo", mapOf("depth" to depth, "ok" to (restored != null)))
                if (restored == null) ActionOutcome.Failed("There's nothing to undo.")
                else ActionOutcome.Done("Undid your last plan change.", listOf(AssistantBlock.PlanBlock()))
            }
            ActionName.PLAN_MAKE -> {
                val request = action.string("request") ?: return ActionOutcome.Failed("What should the plan be about?")
                makePlan(request)
                ActionOutcome.Done()
            }
            ActionName.CHECKOFF -> {
                val plan = plan() ?: return ActionOutcome.Failed(AssistantRouter.NO_PLAN_TEXT)
                val id = action.id("item", 's') ?: return ActionOutcome.Failed("I couldn't find that.")
                val done = action.bool("done") != false
                setCheckedOff(eventId, plan, id, done, "tap")
                val name = bundle.scheduleItem(id)?.name ?: "It"
                ActionOutcome.Done(if (done) "Checked off $name." else "$name isn't checked off any more.")
            }
            ActionName.FOLLOW_LOCK_SCREEN, ActionName.REMINDERS, ActionName.NOTIFICATIONS -> {
                val on = action.bool("on") != false
                if (!host.setSetting(action.name, on)) {
                    _effects.tryEmit(AssistantEffect.RequestNotifications(action))
                    return ActionOutcome.Done("Allow notifications to turn this on.")
                }
                ActionOutcome.Done(settingText(action.name, on), listOf(AssistantBlock.Setting(action.name.key)))
            }
            ActionName.ADD_TO_CALENDAR -> {
                val entries = if (action.bool("plan") == true) plan()?.let { CalendarEntries.forPlan(it, bundle) }.orEmpty()
                else CalendarEntries.forItems(action.refs("ids").filter { it.kind == RefKind.SCHEDULE }.map { it.id }, bundle)
                when (entries.size) {
                    0 -> return ActionOutcome.Failed("There's nothing with a time to add.")
                    1 -> _effects.tryEmit(AssistantEffect.Calendar(entries.single()))
                    else -> _state.update { it.copy(calendar = entries) }
                }
                ActionOutcome.Done()
            }
            ActionName.SHARE_PLAN -> {
                val plan = plan() ?: return ActionOutcome.Failed(AssistantRouter.NO_PLAN_TEXT)
                share(PlanShare.planLink(plan, bundle), "plan", plan.itemIds.size, "My plan for ${bundle.event.name}", "share_sheet")
                ActionOutcome.Done()
            }
            ActionName.SHARE_ITEM -> {
                val id = action.id("id", 's') ?: return ActionOutcome.Failed("I couldn't find that.")
                share(PlanShare.itemLink(id, bundle), "item", 1, bundle.scheduleItem(id)?.name ?: bundle.event.name, "share_sheet")
                ActionOutcome.Done()
            }
            ActionName.OPEN -> {
                host.open(action.string("screen").orEmpty(), EntityRef.of(action.params["id"]))
                ActionOutcome.Done()
            }
            ActionName.DIRECTIONS -> {
                val point = action.id("location", 'l')?.let { bundle.location(it)?.coordinate } ?: return ActionOutcome.Failed("That place has no map position.")
                _effects.tryEmit(AssistantEffect.Directions(point))
                ActionOutcome.Done()
            }
            else -> ActionOutcome.Failed("I can't do that here.")
        }
    }

    private fun opName(op: PlanOp): String = when (op) {
        is PlanOp.Replace -> "replace"
        is PlanOp.Remove -> "remove"
        is PlanOp.Add -> "add"
        is PlanOp.Move -> "move"
        is PlanOp.Keep -> "keep"
        is PlanOp.Unkeep -> "unkeep"
        else -> "constraint"
    }

    private fun applyEdit(eventId: Int, edit: PlanEdit, ops: List<String>, prefix: String = ""): ActionOutcome {
        val started = System.nanoTime()
        val bundle = repository.bundle.value ?: return ActionOutcome.Failed("No event.")
        plans.applyPlan(eventId, edit.after)
        AppLog.info(
            LogCategory.PLANNER, LogEvent.PLAN_EDIT, "Plan edit applied",
            mapOf(
                "ops" to ops, "source" to if (quietSource == ActionSource.MANUAL) "plan" else "assistant", "applied" to true,
                "removed" to edit.diff.removed.size, "added" to edit.diff.added.size, "moved" to edit.diff.moved.size,
                "ms" to (System.nanoTime() - started) / 1_000_000,
            ),
        )
        _state.update { it.copy(canUndo = true) }
        refreshLive()
        return ActionOutcome.Done(
            prefix + AssistantRouter.describe(edit, bundle),
            listOf(
                AssistantBlock.PlanDiffBlock(edit.diff),
                AssistantBlock.Chips(listOf(Chip("Show my plan", AssistantAction(ActionName.PLAN)))),
            ),
        )
    }

    private fun settingText(name: ActionName, on: Boolean) = when (name) {
        ActionName.FOLLOW_LOCK_SCREEN -> if (on) "Following your day on the Lock Screen." else "Stopped following on the Lock Screen."
        ActionName.REMINDERS -> if (on) "Reminders are on." else "Reminders are off."
        else -> if (on) "Notifications from the organizer are on." else "Notifications from the organizer are off."
    }

    /** POST_NOTIFICATIONS answered for a setting that needed it. */
    fun onNotificationPermission(granted: Boolean, action: AssistantAction) {
        if (!granted) {
            append(null, listOf(AssistantBlock.Text("Notifications are off for Attnd, so I can't turn that on.")))
            return
        }
        val on = action.bool("on") != false
        host.setSetting(action.name, on)
        append(null, listOf(AssistantBlock.Text(settingText(action.name, on)), AssistantBlock.Setting(action.name.key)))
    }

    private fun share(link: String, target: String, items: Int, title: String, via: String) {
        AppLog.info(
            LogCategory.UI, LogEvent.SHARE_CREATED, "Share link made ($target)",
            mapOf("target" to target, "items" to items, "payload_len" to link.substringAfterLast('/').length, "via" to via),
        )
        if (via == "share_sheet") _effects.tryEmit(AssistantEffect.Share(link, title))
    }

    /** The share block's link (also logged when the QR or Copy is used). */
    fun shareLink(target: String): String? {
        val bundle = repository.bundle.value ?: return null
        return if (target == "plan") plans.plan(bundle.event.id)?.takeIf { !it.isEmpty }?.let { PlanShare.planLink(it, bundle) }
        else EntityRef.parse(target.removePrefix("item:"))?.let { PlanShare.itemLink(it.id, bundle) }
    }

    fun shareFromBlock(target: String, via: String) {
        val bundle = repository.bundle.value ?: return
        val link = shareLink(target) ?: return
        val isPlan = target == "plan"
        val items = if (isPlan) plans.plan(bundle.event.id)?.itemIds?.size ?: 0 else 1
        val title = if (isPlan) "My plan for ${bundle.event.name}" else EntityRef.parse(target.removePrefix("item:"))?.name(bundle) ?: bundle.event.name
        share(link, if (isPlan) "plan" else "item", items, title, via)
    }

    fun dismissCalendar() = _state.update { it.copy(calendar = null) }

    fun addToCalendar(entry: CalendarEntry) {
        _effects.tryEmit(AssistantEffect.Calendar(entry))
    }

    // ── Plans ───────────────────────────────────────────────────────────────

    private fun makePlan(request: String) {
        _state.update { it.copy(busy = TurnPath.PLAN) }
        scope.launch {
            val plan = host.makePlan(request, "assistant")
            val bundle = repository.bundle.value
            if (plan == null || plan.isEmpty || bundle == null) {
                append(null, listOf(AssistantBlock.Text(host.planError() ?: "Nothing on the schedule fits that. Try another day or fewer limits.")))
                return@launch
            }
            val blocks = listOf(
                AssistantBlock.Text("Here's your plan: ${Planner.summary(plan, bundle.event.zone)}."),
                AssistantBlock.PlanBlock(),
                AssistantBlock.Chips(
                    listOf(
                        Chip("Share my plan", AssistantAction(ActionName.SHARE_PLAN)),
                        Chip("Add my plan to calendar", AssistantAction.calendarPlan()),
                        Chip(PlanVersionKind.CHILL.label + " version", AssistantAction.planVersion(PlanVersionKind.CHILL)),
                        Chip("Undo", AssistantAction(ActionName.PLAN_UNDO)),
                    ),
                ),
            )
            context()?.let { ctx ->
                val validated = BlockValidator.validate(blocks, ctx.blockContext)
                append(null, validated)
                remember(ctx.bundle.event.id, null, AssistantTurn(validated))
            }
            refreshLive()
        }
    }

    /** A shared plan link opened after joining its event (API-015): read-only view with Use / Merge. */
    fun openShared(payload: SharedPayload, joined: Boolean) {
        val bundle = repository.bundle.value ?: return
        val known = PlanShare.resolve(payload, bundle)
        val single = payload.ids.size == 1
        AppLog.notice(
            LogCategory.UI, LogEvent.SHARE_OPENED, "Shared link opened",
            mapOf("target" to if (single) "item" else "plan", "items" to payload.ids.size, "known" to known.size, "action" to "viewed", "joined" to joined),
        )
        val ctx = context() ?: return
        if (known.isEmpty()) {
            append(null, listOf(AssistantBlock.Text("That shared plan's items aren't on the schedule any more.")))
            return
        }
        if (single) {
            val ref = EntityRef(RefKind.SCHEDULE, known.single())
            append(null, BlockValidator.validate(listOf(
                AssistantBlock.Text("Someone shared **${ref.name(bundle)}** with you."),
                AssistantBlock.ItemCard(ref.id),
                AssistantBlock.Chips(AssistantRouter.entityChips(ref, ctx)),
            ), ctx.blockContext))
            return
        }
        plans.addSharedPlan(bundle.event.id, known)
        append(
            null,
            BlockValidator.validate(listOf(
                AssistantBlock.Text("Someone shared a plan with ${known.size} items."),
                AssistantBlock.Timeline(known, title = "Shared plan"),
            ), ctx.blockContext),
            shared = SharedOffer(known, payload.day),
        )
    }

    /** "Use this plan" (merge = false) or "Merge with mine" on a shared plan turn. */
    fun useShared(turnId: Long, merge: Boolean) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        val offer = turn.shared?.takeIf { !it.handled } ?: return
        val bundle = repository.bundle.value ?: return
        val eventId = bundle.event.id
        val now = Instant.now()
        val own = hearts.hearts(eventId)
        val plan = if (merge) SharedPlans.merge(plans.sharedPlans(eventId), own, bundle, plans.plan(eventId), now)
        else SharedPlans.use(offer.ids, offer.day, bundle, own, now)
        AppLog.notice(
            LogCategory.UI, LogEvent.SHARE_OPENED, if (merge) "Shared plan merged" else "Shared plan used",
            mapOf("target" to "plan", "items" to offer.ids.size, "known" to offer.ids.size, "action" to if (merge) "merge" else "use", "joined" to false),
        )
        if (plan == null) {
            append(null, listOf(AssistantBlock.Text("Nothing in that plan is still ahead.")))
            return
        }
        plans.applyPlan(eventId, plan)
        _state.update { s -> s.copy(turns = s.turns.map { if (it.id == turnId) it.copy(shared = offer.copy(handled = true)) else it }) }
        append(
            null,
            listOf(
                AssistantBlock.Text(if (merge) "Merged: ${Planner.summary(plan, bundle.event.zone)}." else "Using the shared plan: ${Planner.summary(plan, bundle.event.zone)}."),
                AssistantBlock.PlanBlock(),
                AssistantBlock.Chips(listOf(Chip("Undo", AssistantAction(ActionName.PLAN_UNDO)))),
            ).let { blocks -> context()?.let { BlockValidator.validate(blocks, it.blockContext) } ?: blocks },
        )
        refreshLive()
    }

    /** "Ask about this" from a detail screen. */
    fun askAbout(ref: EntityRef) {
        val ctx = context() ?: return
        val text = AssistantFlow.askAbout(ref, ctx.bundle) ?: return
        if (ctx.modelAvailable) {
            submit(text, forced = TurnPath.ASK)
        } else {
            finish(text, AssistantFlow.aboutTurn(ref, ctx), TurnSource.RULES, AssistantIntent.SEARCH, System.nanoTime(), ctx)
        }
    }

    // ── Live (spec §6) ──────────────────────────────────────────────────────

    private fun setCheckedOff(eventId: Int, plan: Plan, itemId: Int, done: Boolean, source: String, minutesNear: Long? = null) {
        val updated = plan.copy(checkedOff = if (done) plan.checkedOff + itemId else plan.checkedOff - itemId)
        if (updated == plan) return
        plans.setPlan(eventId, updated)
        AppLog.info(
            LogCategory.LIVE, LogEvent.LIVE_CHECKOFF, if (done) "Checked off" else "Check-off removed",
            mapOf("item_id" to itemId, "source" to source, "done" to done, "minutes_near" to minutesNear),
        )
    }

    /** A check toggle on a plan row (My plan, the assistant's plan block). */
    fun toggleCheckOff(itemId: Int) {
        val bundle = repository.bundle.value ?: return
        val plan = plans.plan(bundle.event.id) ?: return
        setCheckedOff(bundle.event.id, plan, itemId, itemId !in plan.checkedOff, "tap")
        refreshLive()
    }

    /** A position sample while the app is visible (only with the location permission already granted). */
    fun onLocation(point: GeoPoint, at: Instant = Instant.now()) {
        samples.addLast(LocationSample(point, at))
        val cutoff = at.minus(SAMPLE_WINDOW)
        while (samples.size > MAX_SAMPLES || (samples.firstOrNull()?.at?.isBefore(cutoff) == true)) samples.removeFirst()
        val bundle = repository.bundle.value ?: return
        val plan = plans.plan(bundle.event.id)
        if (plan != null && !plan.isEmpty) {
            val checked = LiveAssist.checkOffs(plan, bundle, samples.toList(), at)
            if (checked.isNotEmpty()) {
                var updated: Plan = plan
                checked.forEach { id ->
                    setCheckedOff(bundle.event.id, updated, id, true, "auto", LiveAssist.CHECKOFF_DWELL.toMinutes())
                    updated = plans.plan(bundle.event.id) ?: updated
                }
            }
        }
        refreshLive(at)
    }

    fun dismissSkipped(chip: Chip) {
        chip.action.ops(repository.bundle.value ?: return, Instant.now())?.filterIsInstance<PlanOp.Remove>()?.forEach { dismissedSkips += it.item }
        refreshLive()
    }

    /** Recomputes the live hints (on samples, plan / like changes and once a minute while visible). */
    fun refreshLive(now: Instant = Instant.now()) {
        val bundle = repository.bundle.value
        if (bundle == null) {
            _state.update { it.copy(live = LiveHints(), wantsLocation = false) }
            return
        }
        val eventId = bundle.event.id
        val plan = plans.plan(eventId)?.takeIf { !it.isEmpty }
        val user = samples.lastOrNull()?.takeIf { Duration.between(it.at, now) < RECENT }?.point ?: host.userLocation()
        val hints = LiveHintRules.of(plan, bundle, hearts.hearts(eventId), now, user, samples.toList(), dismissedSkips)
        hints.runningLate?.let { late ->
            if (lateLoggedFor != late.itemId) {
                lateLoggedFor = late.itemId
                val start = bundle.scheduleItem(late.itemId)?.startTime?.toInstant()
                AppLog.notice(
                    LogCategory.LIVE, LogEvent.LIVE_RUNNING_LATE, "Running late (${late.state})",
                    mapOf("item_id" to late.itemId, "minutes_left" to start?.let { Duration.between(now, it).toMinutes() }, "walk_min" to late.walkMinutes, "action" to if (late.chips.size > 1) "swap_offered" else "shown"),
                )
            }
        }
        val today = now.atZone(bundle.event.zone).toLocalDate()
        val day = plan?.days?.firstOrNull { it.date == today }?.items.orEmpty()
        val wants = day.isNotEmpty() && !now.isBefore(day.first().start.minus(LOCATION_LEAD)) && now.isBefore(day.last().end.plus(LOCATION_TAIL))
        _state.update { it.copy(live = hints, wantsLocation = wants, canUndo = plans.history(eventId).canUndo) }
    }

    private companion object {
        val SAMPLE_WINDOW: Duration = Duration.ofHours(3)
        const val MAX_SAMPLES = 600
        val RECENT: Duration = Duration.ofMinutes(5)
        val LOCATION_LEAD: Duration = Duration.ofHours(2)
        val LOCATION_TAIL: Duration = Duration.ofMinutes(30)
    }
}
