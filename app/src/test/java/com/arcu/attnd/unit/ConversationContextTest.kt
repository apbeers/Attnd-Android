package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.AssistantFlow
import com.arcu.attnd.domain.AssistantIntent
import com.arcu.attnd.domain.AssistantModel
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.AssistantTurn
import com.arcu.attnd.domain.BlockJson
import com.arcu.attnd.domain.ChatTranscript
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.Component
import com.arcu.attnd.domain.ConversationContext
import com.arcu.attnd.domain.ConversationTurn
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.RoutedTurn
import com.arcu.attnd.domain.ScheduleEntryKind
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.TurnPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * The assistant's memory (iOS `ConversationContextTests`, same cases): pronouns, ordinals and descriptions resolved
 * against what the answers showed, and the router's My schedule grammar end to end ("find dinner" → "add it to my
 * schedule" → "add it at 7" → "move it to 8" → "remind me 30 min before it"; "add the second one"; the clash
 * question). No model: the router alone. The sequence case is in [PlanProposalTest].
 */
class ConversationContextTest {
    private val bundle = PlannerFixture.bundle
    private fun s(id: Int) = EntityRef(RefKind.SCHEDULE, id)
    private fun l(id: Int) = EntityRef(RefKind.LOCATION, id)

    /** The router with My schedule and memory, no likes or plan. */
    private fun ctx(schedule: MySchedule = MySchedule(), memory: ConversationContext = ConversationContext()) = AssistantContext(
        bundle = bundle, hearts = Hearts(schedule.placeIds, schedule.itemIds), plan = null, now = PlannerFixture.now,
        schedule = schedule, memory = memory,
    )

    private class State(val schedule: MySchedule = MySchedule(), var memory: ConversationContext = ConversationContext())

    /** Runs a turn like the controller: route, apply its schedule actions, remember it. */
    private fun turn(input: String, state: State): RoutedTurn {
        val routed = AssistantRouter.route(input, ctx(state.schedule, state.memory))
        routed.actions.forEach { apply(it, state.schedule) }
        state.memory = state.memory.record(routed.turn)
        return routed
    }

    private fun apply(action: AssistantAction, schedule: MySchedule) {
        val ref = action.scheduleTarget() ?: return
        val target = AssistantRouter.target(ref)
        val at = action.string("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.let { MySchedule.date(it, schedule.entry(target)?.at, PlannerFixture.now, bundle.event.zone) }
        when (action.name) {
            ActionName.SCHEDULE_ADD -> {
                action.scheduleTarget("replace")?.let { schedule.remove(AssistantRouter.target(it)) }
                schedule.add(target, at = if (ref.kind == RefKind.LOCATION) at else null)
            }
            ActionName.SCHEDULE_REMOVE -> schedule.remove(target)
            ActionName.SCHEDULE_MOVE -> schedule.move(target, at)
            ActionName.SCHEDULE_REMINDER -> schedule.setReminder(target, action.int("minutes"))
            else -> Unit
        }
    }

    @Test
    fun resolution() {
        var memory = ConversationContext().record(AssistantTurn(listOf(AssistantBlock.Carousel(listOf(l(6), l(5), l(4)), "Places"))))
        val kinds = setOf(RefKind.SCHEDULE, RefKind.LOCATION)
        assertEquals(l(6), memory.focus)
        assertEquals(listOf(l(6)), memory.resolve("it", kinds, bundle))
        assertEquals(listOf(l(6)), memory.resolve("that one", kinds, bundle))
        assertEquals(listOf(l(6)), memory.resolve("there", kinds, bundle))
        assertEquals(listOf(l(5)), memory.resolve("the second one", kinds, bundle))
        assertEquals(listOf(l(4)), memory.resolve("the last one", kinds, bundle))
        assertEquals(listOf(l(4)), memory.resolve("option 3", kinds, bundle))
        assertEquals(listOf(l(6), l(5)), memory.resolve("both", kinds, bundle))
        assertEquals(listOf(l(6), l(5), l(4)), memory.resolve("them", kinds, bundle))
        assertEquals(listOf(l(6)), memory.resolve("the taco place", kinds, bundle))
        assertEquals(listOf(l(5)), memory.resolve("the bbq one", kinds, bundle))
        assertNull(memory.resolve("the prairie lights", kinds, bundle))
        assertNull(memory.resolve("3", kinds, bundle))
        assertNull(memory.resolve("it", setOf(RefKind.SCHEDULE), bundle))
        // A turn showing nothing keeps the memory; an action's target becomes the focus.
        memory = memory.record(AssistantTurn(listOf(AssistantBlock.Text("When?"))))
        assertEquals(listOf(l(6), l(5), l(4)), memory.shown)
        memory = memory.record(AssistantTurn(listOf(AssistantBlock.Text("Added.")), listOf(AssistantAction.scheduleAdd(l(5), time = "19:00"))))
        assertEquals(l(5), memory.focus)
        assertEquals("schedule_add", memory.lastAction)
        // Stored with the conversation.
        val conversation = AssistantConversation(listOf(ConversationTurn(fromUser = true, text = "hi")), memory)
        val decoded = AssistantConversation.decode(conversation.encode())
        assertEquals(memory, decoded.memory)
        assertEquals(conversation.turns, decoded.turns)
        // Older builds stored the turns array alone.
        assertEquals(1, AssistantConversation.decode("""[{"fromUser":true,"text":"hi"}]""").turns.size)
    }

    @Test
    fun findDinnerAddItMoveItRemindMe() {
        val state = State()
        val found = turn("find dinner", state)
        assertEquals(AssistantIntent.SEARCH, found.intent)
        assertEquals(RefKind.LOCATION, state.memory.focus?.kind)
        val place = state.memory.focus!!
        // A place is saved at once without a time; the chips offer a time.
        val saved = turn("add it to my schedule", state)
        assertEquals(AssistantIntent.SCHEDULE, saved.intent)
        assertEquals(listOf(ActionName.SCHEDULE_ADD), saved.actions.map { it.name })
        assertNotNull(state.schedule.entry(ScheduleEntryKind.PLACE, place.id))
        assertNull(state.schedule.entry(ScheduleEntryKind.PLACE, place.id)?.at)
        val chips = (saved.blocks.last() as AssistantBlock.Chips).chips
        assertTrue(chips.first().label.startsWith("Pick a time: "))
        assertEquals(ActionName.SCHEDULE_MOVE, chips.first().action.name)
        // "add it at 7" → 7:00 PM today.
        val added = turn("add it at 7", state)
        assertEquals(listOf(ActionName.SCHEDULE_ADD), added.actions.map { it.name })
        assertEquals(PlannerFixture.sat("19:00").toInstant(), state.schedule.entry(ScheduleEntryKind.PLACE, place.id)?.at)
        // "move it to 8" → 8:00 PM.
        val moved = turn("move it to 8", state)
        assertEquals(listOf(ActionName.SCHEDULE_MOVE), moved.actions.map { it.name })
        assertEquals(PlannerFixture.sat("20:00").toInstant(), state.schedule.entry(ScheduleEntryKind.PLACE, place.id)?.at)
        // "remind me 30 min before it".
        val reminded = turn("remind me 30 min before it", state)
        assertEquals(listOf(ActionName.SCHEDULE_REMINDER), reminded.actions.map { it.name })
        assertEquals(30, state.schedule.entry(ScheduleEntryKind.PLACE, place.id)?.reminderMinutes)
        // What's on my schedule lists it.
        val mine = turn("what's on my schedule", state)
        assertEquals(AssistantIntent.SCHEDULE, mine.intent)
        assertTrue(BlockJson.encode(mine.turn).toString().contains("\"${place.code}\""))
        // Remove it.
        turn("remove it from my itinerary", state)
        assertNull(state.schedule.entry(ScheduleEntryKind.PLACE, place.id))
    }

    @Test
    fun addTheSecondOne() {
        val state = State()
        turn("what's on tonight", state)
        val second = state.memory.shown[1]
        val added = turn("add the second one", state)
        assertEquals(second, added.actions.first().scheduleTarget())
        assertTrue(second.id in state.schedule.itemIds)
        // Items keep the organizer's time.
        assertTrue(turn("move it to 8", state).actions.isEmpty())
    }

    @Test
    fun clashFlow() {
        val state = State()
        state.schedule.add(ScheduleTarget(SearchKind.SCHEDULE, 113)) // Sundown Choir 6:15 PM
        val clash = turn("add beer tasting", state)
        assertTrue(clash.actions.isEmpty())
        assertEquals("That overlaps **Sundown Choir** at 6:15 PM. Replace it or keep both?", (clash.blocks.first() as AssistantBlock.Text).text)
        val chips = (clash.blocks.last() as AssistantBlock.Chips).chips
        assertEquals(listOf("Replace Sundown Choir", "Keep both"), chips.map { it.label })
        apply(chips[0].action, state.schedule)
        assertEquals(setOf(112), state.schedule.itemIds)
        // Ambiguous names ask which one.
        assertEquals(AssistantIntent.PICK, turn("add stage", state).intent)
    }

    @Test
    fun vocabulary() {
        val state = State()
        assertEquals(listOf(ActionName.SCHEDULE_ADD), turn("put juniper lane in my agenda", state).actions.map { it.name })
        assertEquals(listOf(ActionName.SCHEDULE_ADD), turn("book the prairie lights", state).actions.map { it.name })
        assertEquals(listOf(ActionName.SCHEDULE_REMOVE), turn("drop juniper lane", state).actions.map { it.name })
        assertEquals(listOf(ActionName.SCHEDULE_REMINDER), turn("don't remind me about the prairie lights", state).actions.map { it.name })
        assertEquals(0, state.schedule.entry(ScheduleEntryKind.ITEM, 101)?.reminderMinutes)
        // Small typos go through the search.
        assertEquals(AssistantIntent.SCHEDULE, turn("add the prairie ligts", state).intent)
    }

    @Test
    fun foreignQuestionsGoToTheModel() {
        assertEquals(TurnPath.ASK, AssistantFlow.path(AssistantIntent.NO_MATCH, "¿Dónde hay comida?", modelAvailable = true))
        assertEquals(TurnPath.ASK, AssistantFlow.path(AssistantIntent.SEARCH, "wo gibt es essen", modelAvailable = true))
        assertEquals(TurnPath.SEARCH, AssistantFlow.path(AssistantIntent.SEARCH, "tacos", modelAvailable = true))
        assertEquals(TurnPath.SEARCH, AssistantFlow.path(AssistantIntent.SEARCH, "wo gibt es essen", modelAvailable = false))
        assertFalse(AssistantFlow.looksForeign("where is the food"))
    }

    @Test
    fun chatTranscriptRoundTripAndFollowUps() {
        val chip = Chip("Remind me 30 min before", AssistantAction.scheduleReminder(l(6), minutes = 30))
        val entries = (1..45).map { i ->
            ChatTranscript.Entry(
                i, "q$i", listOf(AssistantBlock.Text("a$i"), AssistantBlock.ComponentBlock(Component("my_schedule")), AssistantBlock.Chips(listOf(chip))),
                fromModel = i % 2 == 0, status = if (i == 45) "Added Taco Row" else null,
            )
        }
        val decoded = ChatTranscript.decode(ChatTranscript(entries).encode())
        assertEquals(ChatTranscript.MAX_TURNS, decoded.entries.size)
        assertEquals(6, decoded.entries.first().id)
        assertEquals(entries.last(), decoded.entries.last())
        assertEquals(listOf("Remind me 30 min before"), ChatTranscript.followUps(decoded.entries).map { it.label })
        assertTrue(ChatTranscript.decode("nonsense").entries.isEmpty())
    }

    @Test
    fun textOnlyModelAnswerGetsItsComponents() {
        // The stub model answers with text alone (build 164's answer, the fixture's names).
        val text = "Right now: Juniper Lane is playing folk music, and you can grab food at Taco Row. Want me to add dinner or another band to your plan?"
        val answer = AssistantModel.assemble(text, emptyList(), emptyList(), ctx())
        assertEquals(listOf("text", "carousel"), answer.blocks.map { it.type })
        assertEquals(listOf(s(109), l(6)), (answer.blocks[1] as AssistantBlock.Carousel).ids)
        // Remembered, so "add it" works.
        val state = State(memory = ConversationContext().record(answer))
        assertEquals(s(109), state.memory.focus)
        assertEquals(listOf(ActionName.SCHEDULE_ADD), turn("add it to my schedule", state).actions.map { it.name })
        assertTrue(ScheduleTarget(SearchKind.SCHEDULE, 109) in state.schedule)
        // One name → a card; generic words and lower-case one-word names → nothing.
        assertEquals(listOf("text", "place_card"), AssistantModel.assemble("Taco Row has tacos.", emptyList(), emptyList(), ctx()).blocks.map { it.type })
        assertEquals(listOf(l(1)), AssistantModel.namedEntities("There's music on the main stage and food everywhere.", ctx()))
        assertEquals(emptyList<EntityRef>(), AssistantModel.namedEntities("Grab some food and music.", ctx()))
        // The model showed something: nothing added.
        val shown = AssistantModel.assemble(text, listOf(AssistantBlock.PlaceCard(6)), emptyList(), ctx())
        assertEquals(listOf("text", "place_card"), shown.blocks.map { it.type })
    }

    @Test
    fun answerTextIsShortAndHasNoIds() {
        val long = "DJ Lone Star is now in your schedule at 1:35 PM. Here's the updated preview: DJ Lone Star (s998) at 1:35 PM, Ana Morales Trio (s995) at 11:30 AM–12:45 PM. Anything else?"
        val turn = AssistantModel.assemble(long, listOf(AssistantBlock.Carousel(listOf(s(104), s(102)))), emptyList(), ctx())
        assertEquals("DJ Lone Star is now in your schedule at 1:35 PM.", (turn.blocks.first() as AssistantBlock.Text).text)
        assertEquals(listOf("text", "carousel"), turn.blocks.map { it.type })
        // Ids never reach the attendee, with or without components; filler goes.
        assertEquals("Try Taco Row or later.", AssistantModel.answerText("Try Taco Row (l6) or [S12] later. Anything else?", withComponents = false))
        assertEquals("It's at near, see you there!", AssistantModel.answerText("It's at l6 near s12, see you there!", withComponents = false))
        assertEquals("Here are the sets.", AssistantModel.answerText("Here are the sets: Juniper Lane, Big Sky Radio.", withComponents = true))
        // Without components the text stays whole (minus ids and filler).
        assertEquals("Gates open at 10. Bring water.", AssistantModel.answerText("Gates open at 10. Bring water.", withComponents = false))
        assertEquals("Anything else?", AssistantModel.answerText("Anything else?", withComponents = false))
    }

    @Test
    fun doneLinesAreHuman() {
        assertEquals("Added Taco Row", AssistantFlow.doneLine("Adding Taco Row…", emptyList()))
        assertEquals("Removed DJ Lone Star", AssistantFlow.doneLine("Removing DJ Lone Star…", emptyList()))
        assertEquals("Planned your day", AssistantFlow.doneLine("Planning your day…", emptyList()))
        assertEquals("Found 4 results", AssistantFlow.doneLine("Searching…", listOf(AssistantBlock.Carousel(listOf(s(104), s(102), l(6), s(103))))))
        assertEquals("Found 1 result", AssistantFlow.doneLine("Searching…", listOf(AssistantBlock.PlaceCard(6))))
        assertNull(AssistantFlow.doneLine("Searching…", listOf(AssistantBlock.Text("Nothing on."))))
        // Show-only and reading turns have no done line.
        assertNull(AssistantFlow.doneLine("Showing carousel…", listOf(AssistantBlock.Carousel(listOf(s(104))))))
        assertNull(AssistantFlow.doneLine("Thinking…", emptyList()))
        assertNull(AssistantFlow.doneLine("Checking your schedule…", emptyList()))
        assertNull(AssistantFlow.doneLine(null, emptyList()))
    }
}
