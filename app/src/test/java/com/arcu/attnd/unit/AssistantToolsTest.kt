package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.AssistantTools
import com.arcu.attnd.domain.AssistantTools.ToolReply
import com.arcu.attnd.domain.AssistantTools.ToolStep
import com.arcu.attnd.domain.AssistantTools.ToolTurn
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.AssistantFlow
import com.arcu.attnd.domain.AssistantIntent
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.TokenBudget
import com.arcu.attnd.domain.Hearts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON tool protocol for Gemini Nano (Android only: iOS uses Foundation Models' native tools with the same
 * semantics). Parsing, one turn's calls, validation of writes, the error budget per tool and the round limit.
 */
class AssistantToolsTest {
    private val ctx = AssistantContext(bundle = PlannerFixture.bundle, hearts = Hearts(), plan = null, now = PlannerFixture.now)

    @Test
    fun parsesCallsAnswersAndAliases() {
        assertEquals(ToolReply.Call("find", kotlinx.serialization.json.buildJsonObject { put("query", JsonPrimitive("tacos")) }),
            AssistantTools.parse("""Sure! {"tool":"find","args":{"query":"tacos"}} and more {"tool":"x"}"""))
        assertEquals(ToolReply.Answer("Taco Row {open}"), AssistantTools.parse("""{"answer":"Taco Row {open}"}"""))
        assertEquals(ToolReply.Answer("Hi"), AssistantTools.parse("""```json
{"text":"Hi"}
```"""))
        val alias = AssistantTools.parse("""{"name":" Find ","arguments":"{\"query\":\"bbq\"}"}""") as ToolReply.Call
        assertEquals("find", alias.tool)
        assertEquals("bbq", (alias.args["query"] as JsonPrimitive).content)
        assertEquals(ToolReply.Call("now_next", kotlinx.serialization.json.JsonObject(emptyMap())), AssistantTools.parse("""{"tool":"now_next"}"""))
        assertTrue(AssistantTools.parse("no json here") is ToolReply.Invalid)
        assertTrue(AssistantTools.parse("""{"foo":1}""") is ToolReply.Invalid)
        assertTrue(AssistantTools.parse("""{"tool":"find","args":[1]}""") is ToolReply.Invalid)
        assertTrue(AssistantTools.parse("""{"tool":"find" """) is ToolReply.Invalid)
    }

    @Test
    fun promptListsEveryTool() {
        val text = AssistantTools.promptSection().joinToString("\n")
        for (name in listOf("find", "event_info", "my_schedule", "now_next", "add_to_schedule", "remove_from_schedule", "move_entry", "set_reminder", "plan_day", "show")) {
            assertTrue(name, text.contains(name))
        }
    }

    @Test
    fun readsThenShowsThenAnswers() {
        val turn = ToolTurn(ctx)
        val found = turn.step(AssistantTools.call("find", mapOf("query" to JsonPrimitive("taco")))) as ToolStep.Continue
        assertTrue(found.result, found.result.startsWith("RESULT find: ") && found.result.contains("[l6] Taco Row"))
        assertEquals("Searching…", found.stage)
        val shown = turn.step(AssistantTools.call("show", mapOf("component" to JsonPrimitive("place_card"), "ids" to buildJsonArray { add(JsonPrimitive("l6")) }))) as ToolStep.Continue
        assertEquals("RESULT show: Shown.", shown.result)
        val done = turn.step("""{"answer":"Taco Row (l6) has tacos. Anything else?"}""") as ToolStep.Finish
        assertEquals(listOf("text", "place_card"), done.turn.blocks.map { it.type })
        assertEquals("Taco Row has tacos.", (done.turn.blocks.first() as AssistantBlock.Text).text)
        assertEquals(2, turn.calls)
    }

    @Test
    fun writesAreValidatedAndRecorded() {
        val turn = ToolTurn(ctx)
        val added = turn.step(AssistantTools.call("add_to_schedule", mapOf("target" to JsonPrimitive("s101")))) as ToolStep.Continue
        assertEquals("Adding The Prairie Lights…", added.stage)
        val reminder = turn.step(AssistantTools.call("set_reminder", mapOf("target" to JsonPrimitive("s101"), "minutes" to JsonPrimitive("30")))) as ToolStep.Continue
        assertTrue(reminder.result, reminder.result.contains("Done"))
        assertEquals(listOf(ActionName.SCHEDULE_ADD, ActionName.SCHEDULE_REMINDER), turn.recordedActions.map { it.name })
        val done = turn.step("""{"answer":"Added."}""") as ToolStep.Finish
        assertEquals(2, done.turn.actions.size)
    }

    @Test
    fun twoErrorsStopATool() {
        val turn = ToolTurn(ctx)
        val bad = AssistantTools.call("add_to_schedule", mapOf("target" to JsonPrimitive("s99999")))
        assertTrue((turn.step(bad) as ToolStep.Continue).result.contains("Error"))
        assertTrue((turn.step(bad) as ToolStep.Continue).result.contains("Error"))
        assertTrue((turn.step(bad) as ToolStep.Continue).result.contains("stop calling add_to_schedule"))
        assertTrue(turn.recordedActions.isEmpty())
        val unknown = turn.step(AssistantTools.call("delete_event")) as ToolStep.Continue
        assertTrue(unknown.result.contains("unknown tool"))
    }

    @Test
    fun roundLimitAndFailures() {
        val turn = ToolTurn(ctx)
        repeat(AssistantTools.MAX_ROUNDS) { turn.step(AssistantTools.call("show", mapOf("component" to JsonPrimitive("place_card"), "ids" to buildJsonArray { add(JsonPrimitive("l6")) }))) }
        // A fifth call ends the turn with what was shown.
        assertTrue(turn.step(AssistantTools.call("now_next")) is ToolStep.Finish)
        // An invalid reply or an answer with nothing to show: the router answers instead.
        assertTrue(ToolTurn(ctx).step("I think Taco Row") is ToolStep.Fail)
        assertTrue(ToolTurn(ctx).step("""{"answer":""}""") is ToolStep.Fail)
    }

    // ── The loop (phase 6b) with a fake model ──────────────────────────────────

    /** A scripted model: each call returns the next reply and records the prompt it got. */
    private class FakeModel(vararg replies: String) {
        val prompts = mutableListOf<String>()
        private val queue = ArrayDeque(replies.toList())
        suspend fun generate(prompt: String): String {
            prompts += prompt
            return queue.removeFirstOrNull() ?: error("no more replies")
        }
    }

    @Test
    fun loopRunsCallsFeedsResultsBackAndAnswers() = runBlocking {
        val model = FakeModel(
            AssistantTools.call("find", mapOf("query" to JsonPrimitive("taco"))),
            AssistantTools.call("show", mapOf("component" to JsonPrimitive("place_card"), "ids" to buildJsonArray { add(JsonPrimitive("l6")) })),
            """{"answer":"Taco Row has tacos."}""",
        )
        val stages = mutableListOf<String>()
        val out = AssistantTools.run("where can I get tacos", ctx, AssistantConversation(), model::generate) { stages += it }
        assertNull(out.failure)
        assertEquals(listOf("text", "place_card"), out.turn!!.blocks.map { it.type })
        assertEquals(2, out.calls)
        assertEquals(3, out.rounds)
        assertEquals(listOf("Searching…", "Showing place card…"), stages)
        assertEquals("Showing place card…", out.stage)
        // Every round re-sends the prompt with the earlier calls and their results, then REPLY:.
        assertTrue(model.prompts[0].contains("TOOLS:") && model.prompts[0].contains("INPUT: where can I get tacos") && model.prompts[0].endsWith("REPLY:"))
        assertTrue(model.prompts[1].contains("CALL {\"tool\":\"find\"") && model.prompts[1].contains("RESULT find: ") && model.prompts[1].contains("[l6] Taco Row"))
        assertTrue(model.prompts[2].contains("RESULT show: Shown."))
        // Each prompt fits the window with room for the reply.
        model.prompts.forEach { assertTrue(TokenBudget.estimate(it) + TokenBudget.REPLY <= TokenBudget.WINDOW) }
    }

    @Test
    fun loopWritesAreRecordedNotRun() = runBlocking {
        val model = FakeModel(
            AssistantTools.call("add_to_schedule", mapOf("target" to JsonPrimitive("s101"))),
            """{"answer":"Added The Prairie Lights."}""",
        )
        val out = AssistantTools.run("add the prairie lights", ctx, AssistantConversation(), model::generate)
        assertEquals(listOf(ActionName.SCHEDULE_ADD), out.turn!!.actions.map { it.name })
        assertEquals("Adding The Prairie Lights…", out.stage)
        assertEquals("Added The Prairie Lights", AssistantFlow.doneLine(out.stage, out.turn.blocks))
    }

    @Test
    fun loopFailuresGoToTheRouter() = runBlocking {
        // Not the protocol on the first reply.
        assertEquals("no json", AssistantTools.run("hi", ctx, AssistantConversation(), FakeModel("Hello there!")::generate).failure)
        // A model error (quota, busy).
        val failing: suspend (String) -> String = { throw IllegalStateException("busy") }
        val error = AssistantTools.run("hi", ctx, AssistantConversation(), failing)
        assertEquals("model: IllegalStateException", error.failure)
        assertNull(error.turn)
        // An answer with nothing to show.
        assertEquals("empty answer", AssistantTools.run("hi", ctx, AssistantConversation(), FakeModel("""{"answer":""}""")::generate).failure)
    }

    @Test
    fun loopAcceptsPlainTextAfterACallAndStopsAtTheRoundLimit() = runBlocking {
        val plain = AssistantTools.run(
            "tacos", ctx, AssistantConversation(),
            FakeModel(AssistantTools.call("find", mapOf("query" to JsonPrimitive("taco"))), "Taco Row serves tacos all day.")::generate,
        )
        assertNull(plain.failure)
        // The text names Taco Row, so its card comes with it.
        assertTrue(plain.turn!!.blocks.map { it.type }.contains("place_card"))

        val show = AssistantTools.call("show", mapOf("component" to JsonPrimitive("place_card"), "ids" to buildJsonArray { add(JsonPrimitive("l6")) }))
        val model = FakeModel(show, show, show, show, show, show)
        val limited = AssistantTools.run("tacos", ctx, AssistantConversation(), model::generate)
        assertNull(limited.failure)
        assertEquals(AssistantTools.MAX_ROUNDS, limited.calls)
        assertEquals(AssistantTools.MAX_ROUNDS + 1, model.prompts.size)
        assertTrue(model.prompts.last().contains("No more tool calls"))
    }

    @Test
    fun olderResultsCollapseToTheirBudget() {
        val long = "RESULT find: " + "x".repeat(4_000)
        val text = AssistantTools.roundPrompt("BASE", listOf("""{"tool":"find"}""" to long, """{"tool":"find"}""" to long, """{"tool":"now_next"}""" to "RESULT now_next: ok"))
        assertTrue(text.contains("RESULT find: (earlier result left out)"))
        assertTrue(text.endsWith("RESULT now_next: ok\nREPLY:"))
        assertTrue(TokenBudget.estimate(text) <= AssistantTools.RESULTS_LIMIT + 50)
    }

    @Test
    fun routerStagesGiveDoneLines() {
        val bundle = PlannerFixture.bundle
        val add = AssistantAction(ActionName.SCHEDULE_ADD, kotlinx.serialization.json.buildJsonObject { put("target", JsonPrimitive(EntityRef(RefKind.SCHEDULE, 101).code)) })
        assertEquals("Adding The Prairie Lights…", AssistantFlow.stageFor(AssistantIntent.SCHEDULE, listOf(add), bundle))
        assertEquals("Searching…", AssistantFlow.stageFor(AssistantIntent.SEARCH, emptyList(), bundle))
        assertNull(AssistantFlow.stageFor(AssistantIntent.FACTS, emptyList(), bundle))
        assertEquals("Planning your day…", AssistantFlow.stageFor(AssistantIntent.PLAN_MAKE, listOf(AssistantAction.planMake("my saturday")), bundle))
    }
}
