package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.AssistantTools
import com.arcu.attnd.domain.AssistantTools.ToolReply
import com.arcu.attnd.domain.AssistantTools.ToolStep
import com.arcu.attnd.domain.AssistantTools.ToolTurn
import com.arcu.attnd.domain.Hearts
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
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
}
