package com.arcu.attnd.unit

import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.AssistantIntent
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.BlockJson
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.unit.AssistantFixture.atMainStage
import com.arcu.attnd.unit.AssistantFixture.clock
import com.arcu.attnd.unit.AssistantFixture.ctx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Assistant spec §9: the rule-based router's exact output (intent, block + action JSON, and the plan
 * after a plan edit) for a fixed list of inputs on [AssistantFixture]. iOS asserts the same JSON
 * (also listed in the scratchpad's assistant_fixtures.md).
 */
class AssistantRouterTest {

    private class Case(val input: String, val ctx: AssistantContext, val intent: AssistantIntent, val json: String, val planAfter: List<String>?)

    private val cases = listOf(
        Case("tacos", ctx(), AssistantIntent.SEARCH,
            """{"blocks":[{"type":"place_card","id":"l6","show":["image","distance","now_next","like","actions"]},{"type":"item_card","id":"s107","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"location","id":"l6"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l6"}},{"label":"Directions","action":"directions","params":{"location":"l6"}}]}],"actions":[]}""",
            null),
        Case("what's on tonight", ctx(), AssistantIntent.WHATS_ON,
            """{"blocks":[{"type":"timeline","ids":["s105","s112","s113","s101","s114","s104"],"title":"Tonight"},{"type":"chips","chips":[{"label":"Show the map","action":"open","params":{"screen":"map"}}]}],"actions":[]}""",
            null),
        Case("food near me", ctx(user = atMainStage), AssistantIntent.NEAR_ME,
            """{"blocks":[{"type":"map","pins":["l6","l4","l5"]},{"type":"carousel","ids":["l6","l4","l5"],"title":"Near you"},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"location","id":"l6"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l6"}},{"label":"Directions","action":"directions","params":{"location":"l6"}}]}],"actions":[]}""",
            null),
        Case("food near me", ctx(), AssistantIntent.NEAR_ME,
            """{"blocks":[{"type":"text","text":"Turn on location to see what's closest."},{"type":"carousel","ids":["l5","l4","l6"],"title":"Places"},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"location","id":"l5"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l5"}},{"label":"Directions","action":"directions","params":{"location":"l5"}}]}],"actions":[]}""",
            null),
        Case("like prairie lights", ctx(hearts = Hearts.NONE), AssistantIntent.LIKE,
            """{"blocks":[{"type":"text","text":"Liked The Prairie Lights."},{"type":"item_card","id":"s101","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Unlike","action":"unlike","params":{"kind":"schedule","id":"s101"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l1"}}]}],"actions":[{"name":"like","params":{"kind":"schedule","id":"s101"}}]}""",
            null),
        Case("like prairie lights", ctx(), AssistantIntent.LIKE,
            """{"blocks":[{"type":"text","text":"You already like The Prairie Lights."},{"type":"item_card","id":"s101","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Unlike","action":"unlike","params":{"kind":"schedule","id":"s101"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l1"}}]}],"actions":[]}""",
            null),
        Case("replace the 3pm thing with something like neon armadillo", ctx(), AssistantIntent.PLAN_EDIT,
            """{"blocks":[{"type":"text","text":"Removed Big Sky Radio. Added Street tacos at home."},{"type":"plan_diff","diff":{"removed":["s103"],"added":["s107"],"moved":[],"meals_removed":[],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_edit","params":{"ops":[{"op":"replace","item":"s103","like":"s114"}]}}]}""",
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "14:15 107", "17:00 105", "18:15 113", "19:00 101", "21:00 104")),
        Case("I already ate", ctx(), AssistantIntent.PLAN_EDIT,
            """{"blocks":[{"type":"text","text":"No lunch break."},{"type":"plan_diff","diff":{"removed":[],"added":[],"moved":[],"meals_removed":["2026-11-14:lunch"],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_edit","params":{"ops":[{"op":"constraint","no_food_until":"2026-11-14T19:00:00Z"}]}}]}""",
            listOf("10:00 111", "11:00 109", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104")),
        Case("done by 9", ctx(), AssistantIntent.PLAN_EDIT,
            """{"blocks":[{"type":"text","text":"Removed DJ Lone Star."},{"type":"plan_diff","diff":{"removed":["s104"],"added":[],"moved":[],"meals_removed":[],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_edit","params":{"ops":[{"op":"constraint","done_by":"21:00"}]}}]}""",
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101")),
        Case("share my plan", ctx(), AssistantIntent.SHARE,
            """{"blocks":[{"type":"share","target":"plan"},{"type":"chips","chips":[{"label":"Add my plan to calendar","action":"add_to_calendar","params":{"plan":true}}]}],"actions":[]}""",
            null),
        Case("why isn't neon armadillo in my plan", ctx(), AssistantIntent.WHY_NOT,
            """{"blocks":[{"type":"text","text":"Neon Armadillo clashes with The Prairie Lights (liked)."},{"type":"item_card","id":"s114","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Swap it in","action":"plan_edit","params":{"ops":[{"op":"add","item":"s114"}]}}]}],"actions":[]}""",
            null),
        Case("turn on reminders", ctx(), AssistantIntent.TOGGLE,
            """{"blocks":[{"type":"confirm","action":"reminders","params":{"on":true},"prompt":"Turn on reminders before liked and planned items?"}],"actions":[]}""",
            null),
        Case("xqzv blorp", ctx(), AssistantIntent.NO_MATCH,
            """{"blocks":[{"type":"text","text":"Nothing matches that. Try one of these:"},{"type":"chips","chips":[{"label":"Happening now","action":"search","params":{"query":"now"}},{"label":"Up next","action":"search","params":{"query":"next"}},{"label":"Liked","action":"liked","params":{}},{"label":"Stage","action":"search","params":{"query":"Stage"}},{"label":"Food","action":"search","params":{"query":"Food"}}]}],"actions":[]}""",
            null),
        Case("", ctx(), AssistantIntent.EMPTY,
            """{"blocks":[{"type":"chips","chips":[{"label":"Happening now","action":"search","params":{"query":"now"}},{"label":"Up next","action":"search","params":{"query":"next"}},{"label":"Liked","action":"liked","params":{}},{"label":"Stage","action":"search","params":{"query":"Stage"}},{"label":"Food","action":"search","params":{"query":"Food"}}]}],"actions":[]}""",
            null),
        Case("like stage", ctx(), AssistantIntent.PICK,
            """{"blocks":[{"type":"text","text":"Which one do you mean?"},{"type":"chips","chips":[{"label":"Main Stage","action":"like","params":{"kind":"location","id":"l1"}},{"label":"Trinity Stage","action":"like","params":{"kind":"location","id":"l2"}}]}],"actions":[]}""",
            null),
        Case("I already ate", ctx(plan = null), AssistantIntent.NO_PLAN,
            """{"blocks":[{"type":"text","text":"You don't have a plan yet."},{"type":"chips","chips":[{"label":"Plan my Saturday around live music and food","action":"plan_make","params":{"request":"Plan my Saturday around live music and food"}},{"label":"I've got 3 hours, show me the highlights","action":"plan_make","params":{"request":"I've got 3 hours, show me the highlights"}},{"label":"Kid-friendly afternoon","action":"plan_make","params":{"request":"Kid-friendly afternoon"}}]}],"actions":[]}""",
            null),
        Case("like beer tasting", ctx(), AssistantIntent.LIKE,
            """{"blocks":[{"type":"text","text":"Liked Beer tasting."},{"type":"item_card","id":"s112","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Add to plan instead of Sundown Choir?","action":"plan_edit","params":{"ops":[{"op":"replace","item":"s113","with":"s112"}]}},{"label":"Unlike","action":"unlike","params":{"kind":"schedule","id":"s112"}},{"label":"Add to my plan","action":"plan_edit","params":{"ops":[{"op":"add","item":"s112"}]}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l8"}}]}],"actions":[{"name":"like","params":{"kind":"schedule","id":"s112"}}]}""",
            null),
        Case("where can I get tacos?", ctx(), AssistantIntent.QUESTION,
            """{"blocks":[{"type":"text","text":"Ask needs on-device AI, which isn't available on this phone. Here's what matches."},{"type":"place_card","id":"l6","show":["image","distance","now_next","like","actions"]},{"type":"item_card","id":"s107","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"location","id":"l6"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l6"}},{"label":"Directions","action":"directions","params":{"location":"l6"}}]}],"actions":[]}""",
            null),
        Case("show the map", ctx(), AssistantIntent.OPEN,
            """{"blocks":[{"type":"text","text":"Opening the map."}],"actions":[{"name":"open","params":{"screen":"map"}}]}""",
            null),
        Case("what's next on my plan", ctx(), AssistantIntent.PLAN_NEXT,
            """{"blocks":[{"type":"text","text":"Next in your plan: **Juniper Lane**, in 1 h."},{"type":"item_card","id":"s109","show":["time","place","distance","like","actions"]},{"type":"countdown","id":"s109"},{"type":"chips","chips":[{"label":"Like","action":"like","params":{"kind":"schedule","id":"s109"}},{"label":"Show on map","action":"open","params":{"screen":"map","id":"l1"}}]}],"actions":[]}""",
            null),
        Case("add my plan to calendar", ctx(), AssistantIntent.CALENDAR,
            """{"blocks":[{"type":"calendar","ids":[],"plan":true}],"actions":[]}""",
            null),
        Case("plan my sunday", ctx(), AssistantIntent.PLAN_MAKE,
            """{"blocks":[{"type":"text","text":"Making your plan."}],"actions":[{"name":"plan_make","params":{"request":"plan my sunday"}}]}""",
            null),
        Case("liked", ctx(), AssistantIntent.LIKED,
            """{"blocks":[{"type":"list","ids":["s101","s114"],"title":"Liked"}],"actions":[]}""",
            null),
        Case("follow on lock screen", ctx(), AssistantIntent.TOGGLE,
            """{"blocks":[{"type":"confirm","action":"follow_lock_screen","params":{"on":true},"prompt":"Follow your day on the Lock Screen?"}],"actions":[]}""",
            null),
        Case("turn off notifications", ctx(), AssistantIntent.TOGGLE,
            """{"blocks":[{"type":"confirm","action":"notifications","params":{"on":false},"prompt":"Turn off notifications from the organizer?"}],"actions":[]}""",
            null),
        Case("chill version", ctx(), AssistantIntent.PLAN_VERSION,
            """{"blocks":[{"type":"text","text":"Chill: Removed DJ Lone Star."},{"type":"plan_diff","diff":{"removed":["s104"],"added":[],"moved":[],"meals_removed":[],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_version","params":{"kind":"chill"}}]}""",
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101")),
        Case("remove juniper lane", ctx(), AssistantIntent.PLAN_EDIT,
            """{"blocks":[{"type":"text","text":"Removed Juniper Lane. Added Brisket 101. No lunch break."},{"type":"plan_diff","diff":{"removed":["s109"],"added":["s106"],"moved":[],"meals_removed":["2026-11-14:lunch"],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_edit","params":{"ops":[{"op":"remove","item":"s109"}]}}]}""",
            listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104")),
        Case("I'm tired", ctx(), AssistantIntent.PLAN_EDIT,
            """{"blocks":[{"type":"text","text":"Removed DJ Lone Star."},{"type":"plan_diff","diff":{"removed":["s104"],"added":[],"moved":[],"meals_removed":[],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_edit","params":{"ops":[{"op":"constraint","pace":"relaxed"}]}}]}""",
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101")),
        Case("how far am I walking", ctx(), AssistantIntent.WALKING,
            """{"blocks":[{"type":"text","text":"0.8 mi walking today"},{"type":"summary","kind":"walking"},{"type":"map","pins":["l1","l2","l3"],"route":["l1","l2","l1","l2","l1","l3"]}],"actions":[]}""",
            null),
        Case("more bbq", ctx(), AssistantIntent.PLAN_EDIT,
            """{"blocks":[{"type":"text","text":"Removed Juniper Lane. Added Brisket 101. No lunch break."},{"type":"plan_diff","diff":{"removed":["s109"],"added":["s106"],"moved":[],"meals_removed":["2026-11-14:lunch"],"meals_added":[],"meals_moved":[]}},{"type":"chips","chips":[{"label":"Undo","action":"plan_undo","params":{}},{"label":"Show my plan","action":"plan","params":{}}]}],"actions":[{"name":"plan_edit","params":{"ops":[{"op":"constraint","more_tag":"bbq"}]}}]}""",
            listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104")),
        Case("why isn't brisket 101 in my plan", ctx(), AssistantIntent.WHY_NOT,
            """{"blocks":[{"type":"text","text":"There isn't time to walk between Brisket 101 and Juniper Lane."},{"type":"item_card","id":"s106","show":["time","place","distance","like","actions"]},{"type":"chips","chips":[{"label":"Swap it in","action":"plan_edit","params":{"ops":[{"op":"add","item":"s106"}]}}]}],"actions":[]}""",
            null),
        Case("compare versions", ctx(), AssistantIntent.PLAN_COMPARE,
            """{"blocks":[{"type":"compare","a":"plan:chill","b":"plan:everything"}],"actions":[]}""",
            null),
    )

    @Test
    fun routerOutputs() {
        for (case in cases) {
            val routed = AssistantRouter.route(case.input, case.ctx)
            assertEquals(case.input, case.intent, routed.intent)
            assertEquals(case.input, case.json, BlockJson.encode(routed.turn).toString())
            assertEquals(case.input, case.planAfter, routed.edit?.let { clock(it.after) })
        }
    }

    @Test
    fun specCasesInWords() {
        // "tacos" → the place card for Taco Row (places lead), then the item, then chips.
        val tacos = AssistantRouter.route("tacos", ctx())
        assertEquals(listOf("place_card", "item_card", "chips"), tacos.blocks.map { it.type })
        // "I already ate" → no_food_until now + 3 h (1:00 PM) and the lunch break gone.
        val ate = AssistantRouter.route("I already ate", ctx())
        assertEquals("""{"ops":[{"op":"constraint","no_food_until":"2026-11-14T19:00:00Z"}]}""", ate.actions.single().params.toString())
        assertEquals("2026-11-14:lunch", ate.edit!!.diff.mealsRemoved.single().encode())
        // "done by 9" at 10 AM → 21:00.
        assertEquals("""{"ops":[{"op":"constraint","done_by":"21:00"}]}""", AssistantRouter.route("done by 9", ctx()).actions.single().params.toString())
        assertEquals("""{"ops":[{"op":"constraint","done_by":"11:00"}]}""", AssistantRouter.route("done by 11", ctx()).actions.single().params.toString())
        assertEquals("""{"ops":[{"op":"constraint","done_by":"21:30"}]}""", AssistantRouter.route("home by 9:30pm", ctx()).actions.single().params.toString())
    }

    @Test
    fun entityResolution() {
        val c = ctx()
        fun r(text: String, kinds: Set<com.arcu.attnd.domain.RefKind>, planned: Boolean = false) = AssistantRouter.resolve(text, kinds, c, planned).toString()
        val s = setOf(com.arcu.attnd.domain.RefKind.SCHEDULE)
        val l = setOf(com.arcu.attnd.domain.RefKind.LOCATION)
        assertEquals("Found(ref=s101)", r("the prairie lights", s))
        assertEquals("Found(ref=s103)", r("the 3pm thing", s, planned = true))
        assertEquals("Found(ref=l6)", r("taco row", l))
        assertEquals("Ambiguous(refs=[l1, l2])", r("stage", l))
        assertEquals("NotFound", r("xqzv", s))
        assertEquals("NotFound", r("the", s))
    }
}
