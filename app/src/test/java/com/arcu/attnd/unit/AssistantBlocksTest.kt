package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionCheck
import com.arcu.attnd.domain.ActionContext
import com.arcu.attnd.domain.ActionDispatcher
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionOutcome
import com.arcu.attnd.domain.ActionRegistry
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.AssistantModel
import com.arcu.attnd.domain.AssistantTurn
import com.arcu.attnd.domain.BlockContext
import com.arcu.attnd.domain.BlockJson
import com.arcu.attnd.domain.BlockValidator
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.ConfirmKind
import com.arcu.attnd.domain.ConversationTurn
import com.arcu.attnd.domain.Dispatch
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.ModelReply
import com.arcu.attnd.domain.PlanDiff
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.TurnSource
import com.arcu.attnd.unit.AssistantFixture.ctx
import com.arcu.attnd.unit.AssistantFixture.plan
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.now
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assistant spec §1–3: the action registry and dispatcher, block JSON and validation, the model path. */
class AssistantBlocksTest {

    private val blockCtx = BlockContext(bundle, plan, now)
    private fun s(id: Int) = EntityRef(RefKind.SCHEDULE, id)
    private fun l(id: Int) = EntityRef(RefKind.LOCATION, id)

    @Test
    fun registry() {
        assertEquals(
            listOf(
                "search", "get_item", "get_location", "get_update", "event_info", "now_next", "whats_on", "liked", "plan", "near_me", "why_not", "route", "day_route",
                "my_schedule", "like", "unlike", "plan_make", "plan_edit", "plan_undo", "plan_version", "checkoff", "follow_lock_screen", "reminders", "notifications",
                "share_plan", "share_item", "open", "directions",
                // My schedule (as iOS, Nov 21, 2026) and the app settings from the chat (iOS batch 7)
                "schedule_add", "schedule_remove", "schedule_move", "schedule_reminder", "location", "leave", "switch_server",
            ),
            ActionName.entries.map { it.key },
        )
        assertEquals(setOf(ActionName.FOLLOW_LOCK_SCREEN, ActionName.REMINDERS, ActionName.NOTIFICATIONS, ActionName.LOCATION, ActionName.LEAVE, ActionName.SWITCH_SERVER), ActionName.entries.filter { it.confirm == ConfirmKind.CHIP }.toSet())
        assertEquals(setOf(ActionName.SHARE_PLAN, ActionName.SHARE_ITEM), ActionName.entries.filter { it.confirm == ConfirmKind.SHEET }.toSet())
        assertEquals(
            setOf(ActionName.LIKE, ActionName.UNLIKE, ActionName.PLAN_MAKE, ActionName.PLAN_EDIT, ActionName.PLAN_VERSION, ActionName.CHECKOFF, ActionName.SCHEDULE_ADD, ActionName.SCHEDULE_REMOVE),
            ActionName.entries.filter { it.undoable }.toSet(),
        )
        assertTrue(ActionName.entries.filter { !it.mutates }.all { it.confirm == ConfirmKind.NONE })
    }

    private fun check(name: String, params: String = "{}") = ActionRegistry.check(name, Json.parseToJsonElement(params) as JsonObject, bundle, now)

    @Test
    fun paramValidation() {
        assertTrue(check("like", """{"kind":"schedule","id":"s101"}""") is ActionCheck.Valid)
        assertTrue(check("like", """{"kind":"location","id":6}""") is ActionCheck.Valid)
        assertEquals(ActionCheck.Invalid("bad id"), check("like", """{"kind":"schedule","id":"l6"}"""))
        assertEquals(ActionCheck.Invalid("bad id"), check("like", """{"kind":"schedule","id":"s999"}"""))
        assertEquals(ActionCheck.Invalid("missing query"), check("search"))
        assertEquals(ActionCheck.Invalid("unknown action"), check("teleport"))
        assertEquals(ActionCheck.Invalid("not available to the assistant"), check("leave_event"))
        assertEquals(ActionCheck.Invalid("bad day"), check("whats_on", """{"day":"2026-12-01"}"""))
        assertTrue(check("whats_on", """{"day":"2026-11-15","from":"18:00"}""") is ActionCheck.Valid)
        assertEquals(ActionCheck.Invalid("bad from"), check("whats_on", """{"from":"6pm"}"""))
        assertEquals(ActionCheck.Invalid("bad screen"), check("open", """{"screen":"debug"}"""))
        assertEquals(ActionCheck.Invalid("bad ops"), check("plan_edit", """{"ops":[{"op":"teleport"}]}"""))
        // No Add to Calendar (as on iOS): the action is never available.
        assertEquals(ActionCheck.Invalid("not available to the assistant"), check("add_to_calendar", """{"ids":["s101"]}"""))
    }

    @Test
    fun dispatcher() {
        val ran = mutableListOf<String>()
        val dispatcher = ActionDispatcher { action -> ran += action.name.key; ActionOutcome.Done() }
        val context = ActionContext(bundle, now, hasLocation = false, hasPlan = true)
        val reminders = AssistantAction.toggle(ActionName.REMINDERS, true)
        val asked = dispatcher.dispatch(reminders, context, ActionSource.RULES)
        assertEquals(Dispatch.NeedsConfirmation(AssistantBlock.Confirm(reminders, "Turn on reminders before things in your schedule?")), asked)
        assertTrue(ran.isEmpty())
        assertEquals(Dispatch.Executed(ActionOutcome.Done(), null), dispatcher.dispatch(reminders, context, ActionSource.RULES, confirmed = true))
        assertEquals(Dispatch.Executed(ActionOutcome.Done(), null), dispatcher.dispatch(reminders, context, ActionSource.MANUAL))
        // Like applies at once with its Undo.
        val like = AssistantAction.like(SearchKind.SCHEDULE, 101)
        assertEquals(Dispatch.Executed(ActionOutcome.Done(), AssistantAction.like(SearchKind.SCHEDULE, 101, liked = false)), dispatcher.dispatch(like, context, ActionSource.MODEL))
        val edit = AssistantAction.planEdit(listOf(com.arcu.attnd.domain.PlanOp.Remove(109)), bundle)
        assertEquals(Dispatch.Executed(ActionOutcome.Done(), AssistantAction(ActionName.PLAN_UNDO)), dispatcher.dispatch(edit, context, ActionSource.RULES))
        assertEquals(Dispatch.Rejected("no plan"), dispatcher.dispatch(edit, context.copy(hasPlan = false), ActionSource.RULES))
        assertEquals(Dispatch.Rejected("needs location"), dispatcher.dispatch(AssistantAction(ActionName.NEAR_ME), context, ActionSource.MODEL))
        assertEquals(Dispatch.Rejected("not in the App Clip"), dispatcher.dispatch(edit, context.copy(isAppClip = true), ActionSource.RULES))
        assertTrue(dispatcher.dispatch(like, context.copy(isAppClip = true), ActionSource.RULES) is Dispatch.Executed)
        assertEquals(listOf("reminders", "reminders", "like", "plan_edit", "like"), ran)
    }

    @Test
    fun jsonRoundTrip() {
        val blocks = listOf(
            AssistantBlock.Text("**Hi**"), AssistantBlock.ItemCard(101), AssistantBlock.PlaceCard(6, listOf("image")), AssistantBlock.UpdateCard(31),
            AssistantBlock.ListBlock(listOf(s(101), l(6)), "Mixed", 5), AssistantBlock.Carousel(listOf(l(4), l(5)), "Food"),
            AssistantBlock.Timeline(listOf(105, 101), title = "Tonight"), AssistantBlock.Timeline(source = "plan"),
            AssistantBlock.MapBlock(listOf(1, 2), route = listOf(1, 2)), AssistantBlock.Gallery(flyer = true), AssistantBlock.NowNext(1), AssistantBlock.Countdown(101),
            AssistantBlock.Compare("plan:chill", "plan:everything"), AssistantBlock.PlanDiffBlock(PlanDiff(removed = listOf(103), added = listOf(107), moved = listOf(109 to 115))),
            AssistantBlock.PlanBlock(java.time.LocalDate.of(2026, 11, 14)), AssistantBlock.Setting("reminders"), AssistantBlock.Summary("walking"),
            AssistantBlock.Share("item:s101"), AssistantBlock.Chips(listOf(Chip("Liked", AssistantAction(ActionName.LIKED)))),
            AssistantBlock.Confirm(AssistantAction.toggle(ActionName.REMINDERS, true), "Sure?"),
        )
        for (block in blocks) {
            assertEquals(BlockJson.Decoded.Ok(block), BlockJson.decode(BlockJson.encode(block)))
        }
        assertEquals("""{"type":"place_card","id":"l6","show":["image"]}""", BlockJson.encode(AssistantBlock.PlaceCard(6, listOf("image"))).toString())
        assertEquals(BlockJson.Decoded.Unknown, BlockJson.decode(Json.parseToJsonElement("""{"type":"hologram"}""")))
        // The calendar block is gone (as on iOS): dropped like any unknown one.
        assertEquals(BlockJson.Decoded.Unknown, BlockJson.decode(Json.parseToJsonElement("""{"type":"calendar","ids":[],"plan":true}""")))
        assertEquals(BlockJson.Decoded.Malformed("item_card"), BlockJson.decode(Json.parseToJsonElement("""{"type":"item_card"}""")))
        assertEquals(BlockJson.Decoded.Malformed("list"), BlockJson.decode(Json.parseToJsonElement("""{"type":"list","ids":["x12"]}""")))
        // Ids as numbers or upper case are read.
        assertEquals(BlockJson.Decoded.Ok(AssistantBlock.ItemCard(101)), BlockJson.decode(Json.parseToJsonElement("""{"type":"item_card","id":101}""")))
        assertEquals(BlockJson.Decoded.Ok(AssistantBlock.Carousel(listOf(s(101), l(6)))), BlockJson.decode(Json.parseToJsonElement("""{"type":"carousel","ids":["S101","[l6]"]}""")))
    }

    @Test
    fun validation() {
        val v = BlockValidator.validate(
            listOf(
                AssistantBlock.Text("   "),
                AssistantBlock.Text("x".repeat(450)),
                AssistantBlock.ItemCard(999),
                AssistantBlock.Carousel(listOf(s(999), l(6))),
                AssistantBlock.ListBlock(listOf(s(101), s(101), l(999), l(1))),
                AssistantBlock.MapBlock(listOf(9, 1, 1, 2)), // the Info Booth has no coordinates
                AssistantBlock.Countdown(111), // already started
                AssistantBlock.Timeline(listOf(104, 101, 999)),
                AssistantBlock.Gallery(listOf(1), flyer = true), // no images, no flyer
                AssistantBlock.Compare("s101", "s101"),
                AssistantBlock.Share("item:s999"),
                AssistantBlock.Chips(listOf(Chip("A", AssistantAction(ActionName.LIKED)))),
            ),
            blockCtx,
        )
        assertEquals(
            listOf(
                AssistantBlock.Text("x".repeat(399) + "…"),
                AssistantBlock.PlaceCard(6),
                AssistantBlock.ListBlock(listOf(s(101), l(1))),
                AssistantBlock.MapBlock(listOf(1, 2)),
                AssistantBlock.Timeline(listOf(101, 104)),
                AssistantBlock.Chips(listOf(Chip("A", AssistantAction(ActionName.LIKED)))),
            ),
            v,
        )
        // More than 8 blocks: 7 + the (last) chips block.
        val chips = AssistantBlock.Chips(
            (1..7).map { Chip("C$it", AssistantAction.search("c$it")) } + Chip("Bad", AssistantAction.search("")),
        )
        val many = (1..10).map { AssistantBlock.Text("t$it") } + AssistantBlock.Chips(listOf(Chip("Old", AssistantAction(ActionName.LIKED)))) + chips
        val cut = BlockValidator.validate(many, blockCtx)
        assertEquals(8, cut.size)
        assertEquals((1..7).map { "t$it" }, cut.take(7).map { (it as AssistantBlock.Text).text })
        assertEquals((1..5).map { "C$it" }, (cut.last() as AssistantBlock.Chips).chips.map { it.label })
        assertEquals(8, BlockValidator.validate((1..10).map { AssistantBlock.Text("t$it") }, blockCtx).size)
        // Plan blocks need a plan.
        assertEquals(emptyList<AssistantBlock>(), BlockValidator.validate(listOf(AssistantBlock.PlanBlock(), AssistantBlock.Share("plan"), AssistantBlock.Summary("walking")), BlockContext(bundle, null, now)))
    }

    @Test
    fun modelTurns() {
        val c = ctx()
        // Valid: unknown block types and ids are dropped, a reminders write becomes a confirm block.
        val raw = """Sure! {"blocks":[{"type":"text","text":"The **Prairie Lights** play at 7."},{"type":"item_card","id":"s101"},{"type":"item_card","id":"s999"},{"type":"hologram"},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"schedule","id":"s101"}},{"label":"Leave","action":"leave_event"}]}],"actions":[{"name":"reminders","params":{"on":true}}]} hope that helps"""
        val reply = AssistantModel.parse(raw, c) as ModelReply.Valid
        assertEquals(
            """{"blocks":[{"type":"text","text":"The **Prairie Lights** play at 7."},{"type":"item_card","id":"s101","show":["time","place","distance","like","actions"]},{"type":"confirm","action":"reminders","params":{"on":true},"prompt":"Turn on reminders before things in your schedule?"},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"schedule","id":"s101"}}]}],"actions":[]}""",
            BlockJson.encode(reply.turn).toString(),
        )
        // A read action is rendered with the router's layout; open stays an action.
        val read = AssistantModel.parse("""{"blocks":[{"type":"text","text":"Here"}],"actions":[{"name":"search","params":{"query":"tacos"}},{"name":"open","params":{"screen":"map"}}]}""", c) as ModelReply.Valid
        assertEquals(listOf("text", "place_card", "item_card"), read.turn.blocks.map { it.type })
        assertEquals(listOf(AssistantAction.open("map")), read.turn.actions)
        // Invalid → the router answers.
        assertEquals(ModelReply.Invalid("malformed item_card"), AssistantModel.parse("""{"blocks":[{"type":"item_card"}]}""", c))
        assertEquals(ModelReply.Invalid("no json"), AssistantModel.parse("I can't do that.", c))
        assertEquals(ModelReply.Invalid("no blocks"), AssistantModel.parse("""{"answer":"x"}""", c))
        assertEquals(ModelReply.Invalid("action leave_event: not available to the assistant"), AssistantModel.parse("""{"blocks":[],"actions":[{"name":"leave_event"}]}""", c))
        assertEquals(ModelReply.Invalid("nothing left"), AssistantModel.parse("""{"blocks":[{"type":"item_card","id":"s12345"}]}""", c))
        val fallback = AssistantModel.resolve("tacos", """{"blocks":[{"type":"item_card"}]}""", c)
        assertEquals(TurnSource.RULES, fallback.source)
        assertEquals(listOf("place_card", "item_card", "chips"), fallback.turn.blocks.map { it.type })
        assertEquals(TurnSource.MODEL, AssistantModel.resolve("tacos", """{"blocks":[{"type":"place_card","id":"l6"}]}""", c).source)
        assertEquals(AssistantTurn(listOf(AssistantBlock.PlaceCard(6))), AssistantModel.resolve("tacos", """{"blocks":[{"type":"place_card","id":"l6"}]}""", c).turn)
    }

    @Test
    fun streamingAndPrompt() {
        val c = ctx()
        val partial = """{"blocks":[{"type":"text","text":"Tonight: {fun}"},{"type":"item_card","id":"s101"},{"type":"carou"""
        assertEquals(listOf(AssistantBlock.Text("Tonight: {fun}"), AssistantBlock.ItemCard(101)), AssistantModel.completeBlocks(partial, c))

        val retrieved = AssistantModel.retrieve("What's good tonight?", c)
        assertEquals(listOf(105, 112, 113, 101, 114, 104), retrieved.take(6).map { it.id })
        assertTrue(retrieved.size <= 12)
        val conversation = (1..8).fold(AssistantConversation()) { acc, i -> acc.with(ConversationTurn(i % 2 == 1, if (i == 1) "Where are the tacos?" else "turn $i")) }
        assertEquals(
            listOf("(earlier: 1 more requests about tacos)", "Attendee: turn 3", "Assistant: turn 4", "Attendee: turn 5", "Assistant: turn 6", "Attendee: turn 7", "Assistant: turn 8"),
            conversation.promptLines(),
        )
        assertEquals(conversation, AssistantConversation.decode(conversation.encode()))
        val prompt = AssistantModel.prompt("What's good tonight?", c, conversation, retrieved)
        assertTrue(prompt.contains("PLAN: 8 items, 10:00 AM – 11:00 PM, with a lunch break — s111; s109; s102; s103; s105; s113; s101; s104"))
        assertTrue(prompt.contains("LIKED: s101 The Prairie Lights; s114 Neon Armadillo"))
        assertTrue(prompt.contains("[s101] The Prairie Lights — Sat 7:00–8:30 PM · Main Stage — tags: live music, country — liked, in plan"))
        assertTrue(!prompt.contains("leave_event"))
        assertTrue(prompt.trimEnd().endsWith("INPUT: What's good tonight?\nJSON:"))
    }
}
