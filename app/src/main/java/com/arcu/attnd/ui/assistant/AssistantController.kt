package com.arcu.attnd.ui.assistant

import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.data.ScheduleController
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
import com.arcu.attnd.domain.AssistantTools
import com.arcu.attnd.domain.ChatTranscript
import com.arcu.attnd.domain.AssistantTurn
import com.arcu.attnd.domain.BlockValidator
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
import com.arcu.attnd.domain.PlanProposal
import com.arcu.attnd.domain.PlanProposals
import com.arcu.attnd.domain.PlanTabFlow
import com.arcu.attnd.domain.ProposalKind
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.ScheduleEntrySource
import com.arcu.attnd.domain.ProposalReply
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.PlanVersions
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.Redact
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SharedPayload
import com.arcu.attnd.domain.SharedPlans
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
import java.time.LocalTime
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

    /** Plans can be made (every phone, with or without on-device AI; never in screenshot mode). */
    fun canPlan(): Boolean

    /** A proposal is now previewed: show the My plan tab. */
    fun onProposal()

    /** A setting toggle; false when turning it on needs POST_NOTIFICATIONS first. */
    fun setSetting(name: ActionName, on: Boolean): Boolean

    fun settingOn(name: ActionName): Boolean
    fun userLocation(): GeoPoint?
    fun units(): DistanceUnits
}

/** A one-shot request the screen carries out with an Activity (share sheet, maps, permission). */
sealed interface AssistantEffect {
    data class Share(val text: String, val title: String) : AssistantEffect
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
    /** The model is still working on this turn (the working line shows under its bubble). */
    val streaming: Boolean = false,
    val shared: SharedOffer? = null,
    /** The done line ("Added Taco Row", "Found 4 results"; [AssistantFlow.doneLine]). */
    val status: String? = null,
)

data class AssistantState(
    val eventId: Int? = null,
    val turns: List<AssistantTurnUi> = emptyList(),
    /** The path of the turn being worked on (busy indicator). */
    val busy: TurnPath? = null,
    /** The working line's text while a turn runs ("Searching…", "Adding Taco Row…"). */
    val stage: String? = null,
    val live: LiveHints = LiveHints(),
    /** The plan has a change to undo. */
    val canUndo: Boolean = false,
    /** A plan item is on now or soon today: sample the position while the app is visible. */
    val wantsLocation: Boolean = false,
    /** Incremented when a turn is added (the screen scrolls to it). */
    val serial: Int = 0,
    /** A change to My schedule waiting for Apply / Cancel: the proposal card under the last turn. */
    val proposal: PlanProposal? = null,
    /** "There are only 3 options." after a reply that names a missing option. */
    val proposalNote: String? = null,
    /** The Undo card after an applied proposal, until the next turn. */
    val undoOffer: UndoOffer? = null,
) {
    /** The follow-up chips above the composer: the last turn's chips. */
    val followUps: List<Chip> get() = turns.lastOrNull()?.takeIf { !it.streaming }?.blocks?.lastOrNull { it is AssistantBlock.Chips }
        ?.let { (it as AssistantBlock.Chips).chips }.orEmpty()

    /** The last turn's last component (the wide layout's second pane), not text or chips. */
    val lastComponent: AssistantBlock? get() = turns.asReversed().firstNotNullOfOrNull { turn ->
        turn.blocks.lastOrNull { it !is AssistantBlock.Text && it !is AssistantBlock.Chips && it !is AssistantBlock.Confirm }
    }
}

/** The Undo card: [label] ("Schedule updated") until the next turn. */
data class UndoOffer(val label: String, val serial: Int)

/**
 * The event assistant on Android (assistant spec §1–8; iOS `AssistantController`, phase 6b): Ask Attnd is
 * one continuous chat per event (≤ [ChatTranscript.MAX_TURNS] turns, stored in [PlanStore] files), each
 * turn the typed text, a working line, then its done line and components; changes to My schedule wait as
 * a proposal card ([PlanProposal]) for Apply / Cancel (or a typed "do it" / "no"), then an Undo card. Every
 * turn goes through the rule-based [AssistantRouter] or, when Gemini Nano is available and
 * [AssistantFlow.path] says so, the model's JSON tool loop ([AssistantTools.run], router fallback). Writes go through
 * [ActionDispatcher] (validation, confirm blocks, Undo) to this [ActionExecutor]. One list (Nov 22,
 * 2026): every plan change (a new plan, an edit, a version, a swap, a sequence, a shared plan) is a
 * proposal over My schedule's day view ([ScheduleController.dayPlan]); Apply turns it into My schedule
 * edits as one step of the one Undo history ([ScheduleController.applyPlan], `undo`). Live hints (running late, check-off, gaps,
 * skipped, recap) come from position samples taken only while the app is visible.
 */
class AssistantController(
    private val scope: CoroutineScope,
    private val repository: EventRepository,
    private val hearts: HeartStore,
    private val schedule: ScheduleController,
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
    private var planJob: Job? = null
    /** A plan request is running (the working line stays up while other turns finish). */
    private var planning = false
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
        val restored = eventId?.let { plans.chat(it).entries }.orEmpty().map { e ->
            AssistantTurnUi(
                e.id.toLong(), e.input, e.blocks, if (e.fromModel) TurnSource.MODEL else TurnSource.RULES, status = e.status,
                // A stored confirm can't be answered again.
                answered = e.blocks.indices.filter { e.blocks[it] is AssistantBlock.Confirm }.toSet(),
            )
        }
        nextId = (restored.maxOfOrNull { it.id } ?: 0L) + 1
        _state.value = AssistantState(eventId = eventId, turns = restored, canUndo = eventId?.let { schedule.history(it).canUndo } == true)
        refreshLive()
    }

    fun clearConversation() {
        val eventId = _state.value.eventId ?: return
        modelJob?.cancel()
        plans.setChat(eventId, ChatTranscript())
        plans.setConversation(eventId, AssistantConversation())
        _state.update { it.copy(turns = emptyList(), busy = null, stage = null, proposal = null, proposalNote = null, undoOffer = null) }
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Assistant conversation cleared", mapOf("name" to "assistant_clear"))
    }

    /** Stores the chat (finished turns only). */
    private fun persist() {
        val s = _state.value
        val eventId = s.eventId ?: return
        plans.setChat(eventId, ChatTranscript(s.turns.filter { !it.streaming }.map { t ->
            ChatTranscript.Entry(t.id.toInt(), t.input, t.blocks, t.source == TurnSource.MODEL, t.status)
        }))
    }

    fun context(now: Instant = Instant.now()): AssistantContext? {
        val bundle = repository.bundle.value ?: return null
        val eventId = bundle.event.id
        val available = host.modelAvailable()
        return AssistantContext(
            bundle = bundle,
            hearts = hearts.hearts(eventId),
            plan = schedule.dayPlan(eventId, bundle),
            now = now,
            user = host.userLocation(),
            canPlan = host.canPlan(),
            modelAvailable = available,
            history = schedule.history(eventId),
            units = host.units(),
            locale = Locale.getDefault(),
            schedule = hearts.schedule(eventId),
            memory = plans.conversation(eventId).memory,
        )
    }

    /** Return / Send: runs the full turn (nothing happens while typing). [forced] = an onboarding question (ASK). */
    fun submit(input: String, forced: TurnPath? = null) {
        val text = input.trim()
        val ctx = context() ?: return
        if (_state.value.turns.any { it.streaming }) return
        _state.update { it.copy(undoOffer = null) }
        // A reply to the previewed edit: "do it", "the second one", "option 3", "no".
        _state.value.proposal?.let { proposal ->
            when (val reply = ProposalReply.parse(text, proposal.options.size)) {
                is ProposalReply.Apply -> {
                    append(text, emptyList())
                    applyProposal(reply.index)
                    return
                }
                ProposalReply.Cancel -> {
                    append(text, emptyList())
                    return cancelProposal()
                }
                is ProposalReply.Invalid -> {
                    append(text, listOf(AssistantBlock.Text(reply.text)))
                    return _state.update { it.copy(proposalNote = reply.text) }
                }
                ProposalReply.None -> settleProposal("Not applied")
            }
        }
        if (text.isEmpty()) {
            finish(null, AssistantRouter.route("", ctx).turn, TurnSource.RULES, AssistantIntent.EMPTY, System.nanoTime(), ctx)
            return
        }
        val started = System.nanoTime()
        val routed = AssistantRouter.route(text, ctx)
        when (AssistantFlow.path(routed.intent, text, ctx.modelAvailable, forced)) {
            TurnPath.SEARCH ->
                if (forced == TurnPath.SEARCH) finish(text, AssistantFlow.searchTurn(text, ctx), TurnSource.RULES, AssistantIntent.SEARCH, started, ctx)
                else finish(text, PlanTabFlow.withoutModel(routed, text, ctx), TurnSource.RULES, routed.intent, started, ctx)
            TurnPath.PLAN -> finish(text, AssistantFlow.planTurn(text, routed, ctx), TurnSource.RULES, routed.intent, started, ctx)
            TurnPath.ASK -> ask(text, ctx, started)
        }
    }

    /**
     * The model path (phase 6b): the user's bubble and the working line at once, then Gemini Nano's JSON tool
     * loop ([AssistantTools.run], non-streaming rounds; the stage follows its calls). Any failure (no JSON,
     * a model error, nothing to show) → the router answers, logged as a fallback.
     */
    private fun ask(text: String, ctx: AssistantContext, started: Long) {
        modelJob?.cancel()
        val eventId = ctx.bundle.event.id
        val id = nextId++
        _state.update {
            it.copy(
                turns = (it.turns + AssistantTurnUi(id, text, emptyList(), TurnSource.MODEL, streaming = true)).takeLast(ChatTranscript.MAX_TURNS),
                busy = TurnPath.ASK, stage = THINKING, serial = it.serial + 1,
            )
        }
        modelJob = scope.launch {
            val outcome = try {
                AssistantTools.run(text, ctx, plans.conversation(eventId), model::toolRound) { stage -> _state.update { it.copy(stage = stage) } }
            } catch (e: CancellationException) {
                _state.update { s -> s.copy(turns = s.turns.filter { it.id != id }, busy = null, stage = null) }
                throw e
            }
            if (outcome.trimmed.isNotEmpty()) {
                AppLog.info(LogCategory.ASSISTANT, LogEvent.ASSISTANT_REQUEST, "Prompt trimmed to its budget", mapOf("trimmed" to outcome.trimmed.joinToString(","), "tokens" to outcome.promptTokens))
            }
            val turn = outcome.turn
            if (turn != null) {
                finish(text, turn, TurnSource.MODEL, AssistantIntent.QUESTION, started, ctx, modelTried = true, stage = outcome.stage, pendingId = id, toolCalls = outcome.calls)
            } else {
                AppLog.warning(
                    LogCategory.ASSISTANT, LogEvent.ASSISTANT_ERROR, "Assistant tool loop failed (${outcome.failure})",
                    mapOf("kind" to "tool_loop", "error" to outcome.failure.orEmpty().substringBefore(':'), "domain" to outcome.failure?.substringAfter(": ", "")),
                )
                val resolved = AssistantModel.resolve(text, null, context() ?: ctx)
                finish(
                    text, resolved.turn, TurnSource.RULES, resolved.routed?.intent ?: AssistantIntent.QUESTION, started, ctx,
                    fallback = true, modelTried = true, pendingId = id, toolCalls = outcome.calls,
                )
            }
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
        stage: String? = null,
        pendingId: Long? = null,
        toolCalls: Int? = null,
    ) {
        val actionSource = if (source == TurnSource.MODEL) ActionSource.MODEL else ActionSource.RULES
        val ms0 = (System.nanoTime() - started) / 1_000_000
        val toolField = toolCalls?.let { mapOf("tool_calls" to it) }.orEmpty()
        // A change to My schedule (router, model or a "Which one?" pick of adds / replaces) waits as a proposal card.
        PlanProposals.fromTurn(turn, ctx)?.let { proposal ->
            append(input, emptyList(), source, replace = pendingId)
            propose(proposal)
            AppLog.info(
                LogCategory.ASSISTANT, LogEvent.ASSISTANT_TURN, "Assistant turn (${if (source == TurnSource.MODEL) "model" else "rules"})",
                mapOf("path" to if (source == TurnSource.MODEL) "model" else "rules", "intent" to intent.name.lowercase(), "ms" to ms0, "blocks" to 0, "fallback" to (modelTried && fallback)) +
                    toolField + (input?.let { Redact.textFields("query", it, AppLog.allowText) } ?: emptyMap()),
            )
            remember(ctx.bundle.event.id, input, AssistantTurn(listOf(AssistantBlock.Text(proposal.summary))))
            return
        }
        var blocks = turn.blocks
        var applied: AssistantAction? = null
        // Plans are made in the background and come back as a proposal card.
        if (turn.actions.any { it.name == ActionName.PLAN_MAKE }) blocks = emptyList()
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
        // A plan request's proposal comes later: no done line yet.
        val done = if (turn.actions.any { it.name == ActionName.PLAN_MAKE }) null
        else AssistantFlow.doneLine(stage ?: AssistantFlow.stageFor(intent, turn.actions, ctx.bundle), validated)
        append(input, validated, source, applied, status = done, replace = pendingId)
        val ms = (System.nanoTime() - started) / 1_000_000
        AppLog.info(
            LogCategory.ASSISTANT, LogEvent.ASSISTANT_TURN, "Assistant turn (${if (source == TurnSource.MODEL) "model" else "rules"})",
            mapOf(
                "path" to if (source == TurnSource.MODEL) "model" else "rules",
                "intent" to intent.name.lowercase(),
                "ms" to ms,
                "blocks" to validated.size,
                "fallback" to (modelTried && fallback),
            ) + toolField + (input?.let { Redact.textFields("query", it, AppLog.allowText) } ?: emptyMap()),
        )
        remember(ctx.bundle.event.id, input, AssistantTurn(validated))
    }

    /**
     * Adds a turn to the chat (≤ [ChatTranscript.MAX_TURNS]), or replaces the pending one [replace]; a turn
     * needs a bubble ([input]) or blocks. Stores the chat.
     */
    private fun append(
        input: String?,
        blocks: List<AssistantBlock>,
        source: TurnSource = TurnSource.RULES,
        applied: AssistantAction? = null,
        shared: SharedOffer? = null,
        status: String? = null,
        replace: Long? = null,
    ) {
        val eventId = _state.value.eventId
        if (blocks.isEmpty() && shared == null && input == null) {
            _state.update { s -> s.copy(busy = if (planning) TurnPath.PLAN else null, stage = if (planning) PLANNING else null, turns = s.turns.filter { it.id != replace }) }
            return
        }
        blocks.forEach { block ->
            AppLog.debug(LogCategory.ASSISTANT, LogEvent.ASSISTANT_BLOCK, "Block ${block.type}", mapOf("type" to block.type, "ids" to AssistantFlow.refs(block).size))
        }
        _state.update {
            val turn = AssistantTurnUi(replace ?: nextId++, input, blocks, source, applied = applied, shared = shared, status = status)
            val turns = if (replace != null && it.turns.any { t -> t.id == replace }) it.turns.map { t -> if (t.id == replace) turn else t } else it.turns + turn
            it.copy(
                turns = turns.takeLast(ChatTranscript.MAX_TURNS),
                busy = if (planning) TurnPath.PLAN else null,
                stage = if (planning) PLANNING else null,
                serial = it.serial + 1,
                canUndo = eventId?.let { id -> schedule.history(id).canUndo } == true,
            )
        }
        persist()
    }

    /** The model's conversation: the user's text and what the assistant answered (text only). */
    private fun remember(eventId: Int, input: String?, turn: AssistantTurn) {
        val bundle = repository.bundle.value ?: return
        var conversation = plans.conversation(eventId)
        if (input != null) conversation = conversation.with(ConversationTurn(true, input))
        conversation = conversation.with(ConversationTurn(false, AssistantFlow.replyText(turn, bundle))).remembering(turn)
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
        // Plan edits from chips, hints and buttons wait as a proposal card first.
        if (PlanProposals.isEdit(action)) {
            PlanProposals.fromAction(action, ctx)?.let { proposal ->
                if (label != null) append(label, emptyList())
                propose(proposal)
                return null
            }
        }
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

    // ── Proposals (plan tab spec) ───────────────────────────────────────────

    private fun propose(proposal: PlanProposal) {
        settleProposal("Not applied")
        _state.update { it.copy(proposal = proposal, proposalNote = null, busy = null, stage = null, undoOffer = null, serial = it.serial + 1) }
        host.onProposal()
    }

    /** A pending proposal ends without Apply ([line] = what the chat keeps of it: "Cancelled", "Not applied"). */
    private fun settleProposal(line: String) {
        val proposal = _state.value.proposal ?: return
        _state.update { it.copy(proposal = null, proposalNote = null) }
        append(null, listOf(AssistantBlock.Text("${proposal.summary} · $line")))
    }

    /** "no" / "cancel" / Back / Cancel on the card: nothing changes. */
    fun cancelProposal() {
        val proposal = _state.value.proposal ?: return
        proposal.first.action?.let { logAction(it, "declined", ConfirmKind.CHIP) }
        settleProposal("Cancelled")
    }

    /**
     * Applies option [index] of the previewed edit (a tapped row, the pill's Apply, "do it", "the
     * second one"): through the dispatcher with Undo; a shared plan is stored as it is. The screen
     * stays where it is and shows the Undo pill. Returns a message when it failed.
     */
    fun applyProposal(index: Int = 0): String? {
        val proposal = _state.value.proposal ?: return null
        val option = proposal.options.getOrNull(index) ?: return null
        val bundle = repository.bundle.value ?: return null
        val eventId = bundle.event.id
        val action = option.action
        if (action == null && proposal.kind == ProposalKind.SEQUENCE) {
            // "Music, food, then more music": the previewed plan's new entries go into My schedule.
            schedule.applyPlan(eventId, option.edit.after, source = ScheduleEntrySource.ASSISTANT, keepPreferences = false)
        } else if (action == null && proposal.kind == ProposalKind.PLAN) {
            // A new plan (one list §3): its items and meal places go into My schedule.
            val applied = schedule.applyPlan(eventId, option.edit.after)
            AppLog.info(
                LogCategory.PLANNER, LogEvent.PLAN_EDIT, "New plan applied",
                mapOf("ops" to listOf("add"), "source" to "plan", "applied" to true, "removed" to applied.removes.size, "added" to applied.adds.size, "moved" to 0),
            )
        } else if (action == null) {
            // Shared plan: "Use this plan" / "Merge with mine".
            val merge = index == 1
            schedule.applyPlan(eventId, option.edit.after, source = ScheduleEntrySource.SHARED, keepPreferences = false, reason = SHARED_REASON)
            AppLog.notice(
                LogCategory.UI, LogEvent.SHARE_OPENED, if (merge) "Shared plan merged" else "Shared plan used",
                mapOf("target" to "plan", "items" to option.edit.after.itemIds.size, "known" to option.edit.after.itemIds.size, "action" to if (merge) "merge" else "use", "joined" to false),
            )
        } else {
            val ctx = context() ?: return null
            quietSource = ActionSource.MANUAL
            val result = try {
                dispatcher.dispatch(action, actionContext(ctx), ActionSource.MANUAL)
            } finally {
                quietSource = null
            }
            val failure = when (result) {
                is Dispatch.Executed -> (result.outcome as? ActionOutcome.Failed)?.reason
                is Dispatch.Rejected -> rejectText(result.reason)
                is Dispatch.NeedsConfirmation -> null
            }
            if (failure != null) {
                logAction(action, "failed")
                _state.update { it.copy(proposal = null, proposalNote = null) }
                append(null, listOf(AssistantBlock.Text(failure)))
                return failure
            }
            logAction(action, "applied", ConfirmKind.CHIP)
        }
        _state.update { it.copy(proposal = null, proposalNote = null) }
        append(null, listOf(AssistantBlock.Text(proposal.summary)), status = if (proposal.kind == ProposalKind.SHARED) "Added the shared plan" else "Updated your schedule")
        _state.update {
            it.copy(
                undoOffer = UndoOffer(if (proposal.kind == ProposalKind.SHARED) "Shared plan added" else "Schedule updated", it.serial + 1),
                canUndo = schedule.history(eventId).canUndo,
            )
        }
        refreshLive()
        return null
    }

    /** The Undo pill (or "undo" typed): My schedule as it was before the last change (one Undo history). */
    fun undoLast() {
        val ctx = context() ?: return
        val result = dispatcher.dispatch(AssistantAction(ActionName.PLAN_UNDO), actionContext(ctx), ActionSource.MANUAL)
        logAction(AssistantAction(ActionName.PLAN_UNDO), "applied")
        _state.update { it.copy(undoOffer = null, canUndo = schedule.history(ctx.bundle.event.id).canUndo) }
        val message = ((result as? Dispatch.Executed)?.outcome as? ActionOutcome.Done)?.message ?: "There's nothing to undo."
        append(null, listOf(AssistantBlock.Text(message)))
        refreshLive()
    }

    /** The Undo card was dismissed ([serial] = the offer that was shown). */
    fun dismissUndo(serial: Int? = null) = _state.update { if (serial == null || it.undoOffer?.serial == serial) it.copy(undoOffer = null) else it }

    fun chip(chip: Chip) {
        run(chip.action, chip.label)
    }

    /** A `confirm` block's Yes / No. */
    fun answer(turnId: Long, index: Int, yes: Boolean) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        val confirm = turn.blocks.getOrNull(index) as? AssistantBlock.Confirm ?: return
        if (index in turn.answered) return
        _state.update { s -> s.copy(undoOffer = null, turns = s.turns.map { if (it.id == turnId) it.copy(answered = it.answered + index) else it }) }
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
            _state.update { s -> s.copy(turns = s.turns.map { if (it.id == turnId) it.copy(undone = true) else it }, canUndo = schedule.history(ctx.bundle.event.id).canUndo) }
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
            _state.update { s -> s.copy(turns = s.turns.map { if (it.id == turnId) it.copy(undone = false) else it }, canUndo = schedule.history(ctx.bundle.event.id).canUndo) }
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
        fun plan() = schedule.dayPlan(eventId, bundle)
        return when (action.name) {
            ActionName.LIKE, ActionName.UNLIKE -> {
                val (kind, id) = action.likeTarget() ?: return ActionOutcome.Failed("I couldn't find that.")
                val liked = action.name == ActionName.LIKE
                host.setLiked(kind, id, liked)
                val ref = EntityRef(if (kind == SearchKind.SCHEDULE) RefKind.SCHEDULE else RefKind.LOCATION, id)
                val name = ref.name(bundle) ?: "it"
                val clash = if (liked && kind == SearchKind.SCHEDULE) plan()?.let { LiveAssist.likeClash(id, it, bundle) } else null
                ActionOutcome.Done(
                    if (liked) "Added $name to your schedule." else "Removed $name from your schedule.",
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
                val restored = schedule.undo(eventId)
                val depth = schedule.history(eventId).snapshots.size
                AppLog.info(LogCategory.PLANNER, LogEvent.PLAN_UNDO, "Plan undo", mapOf("depth" to depth, "ok" to restored))
                refreshLive()
                if (!restored) ActionOutcome.Failed("There's nothing to undo.")
                else ActionOutcome.Done("Undid your last schedule change.", listOf(AssistantBlock.PlanBlock()))
            }
            ActionName.PLAN_MAKE -> {
                val request = action.string("request") ?: return ActionOutcome.Failed("What should the plan be about?")
                planRequest(request)
                ActionOutcome.Done()
            }
            ActionName.CHECKOFF -> {
                plan() ?: return ActionOutcome.Failed(AssistantRouter.NO_PLAN_TEXT)
                val id = action.id("item", 's') ?: return ActionOutcome.Failed("I couldn't find that.")
                val done = action.bool("done") != false
                setCheckedOff(eventId, id, done, "tap")
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
            ActionName.SHARE_PLAN -> {
                val plan = plan() ?: return ActionOutcome.Failed(AssistantRouter.NO_PLAN_TEXT)
                share(PlanShare.planLink(plan, bundle), "plan", plan.itemIds.size, "My schedule for ${bundle.event.name}", "share_sheet")
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
            ActionName.SCHEDULE_ADD, ActionName.SCHEDULE_REMOVE, ActionName.SCHEDULE_MOVE, ActionName.SCHEDULE_REMINDER -> scheduleAction(action, bundle)
            else -> ActionOutcome.Failed("I can't do that here.")
        }
    }

    /** My schedule's actions from the router / model (phase 6a; the chat of 6b keeps them). */
    private fun scheduleAction(action: AssistantAction, bundle: EventBundle): ActionOutcome {
        val ref = action.scheduleTarget() ?: return ActionOutcome.Failed("I couldn't find that.")
        val eventId = bundle.event.id
        val target = ScheduleTarget(ref.kind.searchKind, ref.id)
        val name = ref.name(bundle) ?: "it"
        val zone = bundle.event.zone
        fun at(time: String?): Instant? = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.let { MySchedule.date(it, hearts.schedule(eventId).entry(target)?.at, Instant.now(), zone) }
        return when (action.name) {
            ActionName.SCHEDULE_ADD -> {
                action.scheduleTarget("replace")?.let { host.setLiked(it.kind.searchKind, it.id, false) }
                // Added by the assistant (one list §1: `source: assistant`), with the API-009 count.
                schedule.add(eventId, target, source = ScheduleEntrySource.ASSISTANT)
                refreshLive()
                if (ref.kind == RefKind.LOCATION) at(action.string("time"))?.let { hearts.move(eventId, target, it) }
                ActionOutcome.Done("Added $name to your schedule.", listOf(BlockValidator.card(ref)))
            }
            ActionName.SCHEDULE_REMOVE -> {
                host.setLiked(ref.kind.searchKind, ref.id, false)
                ActionOutcome.Done("Removed $name from your schedule.")
            }
            ActionName.SCHEDULE_MOVE -> {
                val time = at(action.string("time")) ?: return ActionOutcome.Failed("What time?")
                if (target !in hearts.schedule(eventId)) host.setLiked(ref.kind.searchKind, ref.id, true)
                hearts.move(eventId, target, time)
                ActionOutcome.Done("Moved $name.")
            }
            else -> {
                hearts.setReminder(eventId, target, action.int("minutes"))
                ActionOutcome.Done("Reminder set for $name.")
            }
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
        // One list: the edit's result becomes edits to My schedule; what the request removed (excluded)
        // leaves even when the person added it.
        schedule.applyPlan(eventId, edit.after, explicit = edit.after.constraints.excluded)
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
                AssistantBlock.Chips(listOf(Chip("Show my schedule", AssistantAction(ActionName.PLAN)))),
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
        return when (target) {
            "plan" -> schedule.dayPlan(bundle.event.id, bundle)?.let { PlanShare.planLink(it, bundle) }
            "schedule" -> PlanShare.scheduleLink(schedule.schedule(bundle.event.id), bundle)
            else -> EntityRef.parse(target.removePrefix("item:"))?.let { PlanShare.itemLink(it.id, bundle) }
        }
    }

    fun shareFromBlock(target: String, via: String) {
        val bundle = repository.bundle.value ?: return
        val link = shareLink(target) ?: return
        val isPlan = target == "plan" || target == "schedule"
        val items = when (target) {
            "plan" -> schedule.dayPlan(bundle.event.id, bundle)?.itemIds?.size ?: 0
            "schedule" -> schedule.schedule(bundle.event.id).itemIds.size
            else -> 1
        }
        val title = if (isPlan) "My schedule for ${bundle.event.name}" else EntityRef.parse(target.removePrefix("item:"))?.name(bundle) ?: bundle.event.name
        share(link, if (isPlan) "plan" else "item", items, title, via)
    }

    // ── Plans ───────────────────────────────────────────────────────────────

    /**
     * Plans for a request (the model reads it when available) and previews the result as a proposal
     * (one list §3): Apply adds it to My schedule, Cancel / "no" / another turn drops it.
     */
    fun planRequest(request: String, source: String = "assistant") {
        if (request.isBlank()) return
        // An onboarding example is the person's own request: its bubble first.
        if (source == "example") append(request, emptyList())
        planJob?.cancel()
        planning = true
        _state.update { it.copy(busy = TurnPath.PLAN, stage = PLANNING) }
        planJob = scope.launch {
            val plan = try {
                host.makePlan(request, source)
            } finally {
                planning = false
                _state.update { it.copy(busy = null, stage = null) }
            }
            val bundle = repository.bundle.value
            if (plan == null || plan.isEmpty || bundle == null) {
                append(null, listOf(AssistantBlock.Text(host.planError() ?: "Nothing on the schedule fits that. Try another day or fewer limits.")))
                return@launch
            }
            val eventId = bundle.event.id
            val preview = schedule.schedule(eventId).proposal(plan, bundle)
            propose(PlanProposals.newPlan(schedule.dayPlan(eventId, bundle), plan, preview, bundle))
            remember(eventId, null, AssistantTurn(listOf(AssistantBlock.Text("Proposed: ${preview.title.lowercase()} (${preview.pillText})."))))
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
        // Previewed in place: "Use this plan" / "Merge with mine" (plan tab spec).
        val eventId = bundle.event.id
        val own = hearts.hearts(eventId)
        val current = schedule.dayPlan(eventId, bundle)
        val use = SharedPlans.use(known, payload.day, bundle, own, ctx.now)
        if (use == null) {
            append(null, listOf(AssistantBlock.Text("Nothing in that plan is still ahead.")))
            return
        }
        val merge = SharedPlans.merge(plans.sharedPlans(eventId), own, bundle, current, ctx.now)
        propose(PlanProposals.shared(current, use, merge, bundle))
    }

    // ── Live (spec §6) ──────────────────────────────────────────────────────

    /** Check-offs (tap or auto): a per-event set in My schedule's storage, outside the Undo history. */
    private fun setCheckedOff(eventId: Int, itemId: Int, done: Boolean, source: String, minutesNear: Long? = null) {
        if (!schedule.setCheckedOff(eventId, itemId, done)) return
        AppLog.info(
            LogCategory.LIVE, LogEvent.LIVE_CHECKOFF, if (done) "Checked off" else "Check-off removed",
            mapOf("item_id" to itemId, "source" to source, "done" to done, "minutes_near" to minutesNear),
        )
    }

    /** A check toggle on a schedule row (Ask Attnd's day, the assistant's plan block). */
    fun toggleCheckOff(itemId: Int) {
        val eventId = repository.bundle.value?.event?.id ?: return
        setCheckedOff(eventId, itemId, itemId !in schedule.checkedOff(eventId), "tap")
        refreshLive()
    }

    /** A position sample while the app is visible (only with the location permission already granted). */
    fun onLocation(point: GeoPoint, at: Instant = Instant.now()) {
        samples.addLast(LocationSample(point, at))
        val cutoff = at.minus(SAMPLE_WINDOW)
        while (samples.size > MAX_SAMPLES || (samples.firstOrNull()?.at?.isBefore(cutoff) == true)) samples.removeFirst()
        val bundle = repository.bundle.value ?: return
        val plan = schedule.dayPlan(bundle.event.id, bundle)
        if (plan != null) {
            LiveAssist.checkOffs(plan, bundle, samples.toList(), at).sorted().forEach { id ->
                setCheckedOff(bundle.event.id, id, true, "auto", LiveAssist.CHECKOFF_DWELL.toMinutes())
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
        val plan = schedule.dayPlan(eventId, bundle)
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
        _state.update { it.copy(live = hints, wantsLocation = wants, canUndo = schedule.history(eventId).canUndo) }
    }

    private companion object {
        const val THINKING = "Thinking…"
        const val PLANNING = "Planning your day…"

        /** The reason of entries a shared plan added (one list §1). */
        const val SHARED_REASON = "In a shared plan"
        val SAMPLE_WINDOW: Duration = Duration.ofHours(3)
        const val MAX_SAMPLES = 600
        val RECENT: Duration = Duration.ofMinutes(5)
        val LOCATION_LEAD: Duration = Duration.ofHours(2)
        val LOCATION_TAIL: Duration = Duration.ofMinutes(30)
    }
}
