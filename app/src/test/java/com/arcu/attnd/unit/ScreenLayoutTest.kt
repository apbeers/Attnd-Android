package com.arcu.attnd.unit

import com.arcu.attnd.domain.AboutText
import com.arcu.attnd.domain.DetailColumns
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.FoldRules
import com.arcu.attnd.domain.HomeColumns
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.NotificationPriority
import com.arcu.attnd.domain.OnboardingLayout
import com.arcu.attnd.domain.Performer
import com.arcu.attnd.domain.ScheduleItemContext
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.domain.ScreenLayout
import com.arcu.attnd.domain.ScreenLayouts
import com.arcu.attnd.domain.SectionCatalogue
import com.arcu.attnd.domain.TabListLayout
import com.arcu.attnd.domain.TabletopSplit
import com.arcu.attnd.domain.UpdatesLayout
import com.arcu.attnd.unit.PlannerFixture.bundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * API-024 / API-027 server-driven screens (`ScreenLayout.kt`): validation, fallbacks, overrides, conditions, facts
 * and decoding, on the planner fixture (now Sat Nov 14 2026 10:00 AM America/Chicago). The iOS `ScreenLayoutTests`
 * cases one for one (and attnd-web `events/tests_layout.py` where they overlap), plus `HomeColumnWidths` from iOS
 * `DuoFeaturesTests`. Not here (other phases): `live_activity` decoding (phase 1, `ApiMapperTest`), the Home tab /
 * display decoding (3a, 1), preview phones and push jitter (9, 1), the saved copy's ETag (5b), the App Clip handover
 * and assistant files (iOS only), setting commands (6a).
 */
class ScreenLayoutTest {

    private val state = ScreenFacts.State(now = PlannerFixture.now)

    private fun types(sections: List<LayoutSection>) = sections.map { it.type }

    private fun layouts(json: String): ScreenLayouts = ScreenLayouts.decode(Json.parseToJsonElement(json))!!

    // Defaults (today's screens)

    @Test
    fun defaultsAreTodaysScreens() {
        assertEquals(listOf("header", "about", "performers", "good_to_know", "links", "place_link"), types(ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, null)))
        assertEquals(
            listOf("header", "walk_map", "about", "hours", "good_to_know", "menu", "links", "at_this_location"),
            types(ScreenLayout.sections(LayoutScreen.PLACE_DETAIL, null)),
        )
        assertEquals(
            listOf("header", "about", "faq", "amenities", "getting_there", "accessibility", "policies", "weather_plan", "links"),
            types(ScreenLayout.sections(LayoutScreen.EVENT_INFO, null)),
        )
        assertEquals(listOf("header", "website", "event_info_link"), types(ScreenLayout.sections(LayoutScreen.OPTIONS_EVENT, null)))
        assertEquals(listOf("now_playing", "selected_place"), types(ScreenLayout.sections(LayoutScreen.MAP_OVERLAY, null)))
        assertEquals(listOf("time", "status", "tags"), ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, null).first().show)
    }

    @Test
    fun everyDefaultSectionIsInTheCatalogue() {
        // Catalogue 2's tab and onboarding screens have no built-in layout: without one the app draws today's tab.
        val ownScreens = setOf(LayoutScreen.SCHEDULE_TAB, LayoutScreen.PLACES_TAB, LayoutScreen.UPDATES_TAB, LayoutScreen.ONBOARDING)
        for (screen in LayoutScreen.entries) {
            val defaults = ScreenLayout.DEFAULTS[screen].orEmpty()
            assertEquals("$screen has a default", screen in ownScreens, defaults.isEmpty())
            assertEquals("$screen defaults are valid as they are", defaults, ScreenLayout.validate(defaults, screen))
        }
    }

    @Test
    fun itemDetailVisibilityFollowsTheData() {
        // Gates open: a description, no location, no context, no model.
        val gates = bundle.scheduleItem(111)!!
        assertEquals(listOf("header", "about", "place_link"), types(ScreenLayout.resolve(LayoutScreen.ITEM_DETAIL, null, ScreenFacts.item(gates, bundle, state))))
        // No Ask about this any more, with or without the model.
        val withModel = state.copy(modelAvailable = true)
        assertEquals(listOf("header", "about", "place_link"), types(ScreenLayout.resolve(LayoutScreen.ITEM_DETAIL, null, ScreenFacts.item(gates, bundle, withModel))))
        // Context parts.
        val item = bundle.scheduleItem(109)!!.copy(context = ScheduleItemContext(performers = listOf(Performer("Juniper Lane")), price = "$5"))
        assertEquals(
            listOf("header", "performers", "good_to_know", "place_link"),
            types(ScreenLayout.resolve(LayoutScreen.ITEM_DETAIL, null, ScreenFacts.item(item, bundle, state))),
        )
    }

    @Test
    fun placeDetailVisibilityFollowsTheData() {
        val info = bundle.location(9)!! // no coordinate, no items
        assertEquals(listOf("header"), types(ScreenLayout.resolve(LayoutScreen.PLACE_DETAIL, null, ScreenFacts.place(info, bundle, state))))
        val main = bundle.location(1)!!
        assertEquals(listOf("header", "walk_map", "at_this_location"), types(ScreenLayout.resolve(LayoutScreen.PLACE_DETAIL, null, ScreenFacts.place(main, bundle, state))))
    }

    // Validation and fallback

    @Test
    fun validationDropsUnknownDuplicatesAndCleans() {
        val raw = listOf(
            LayoutSection("HEADER", show = listOf("tags", "sparkles", "tags")),
            LayoutSection("hologram"),
            LayoutSection("about", title = "   "),
            LayoutSection("header"),
            LayoutSection("links", title = "x".repeat(90), limit = 400),
        )
        val kept = ScreenLayout.validate(raw, LayoutScreen.ITEM_DETAIL)
        assertEquals(listOf("header", "about", "links"), types(kept))
        assertEquals(listOf("tags"), kept[0].show)
        assertNull(kept[1].title)
        assertEquals(ScreenLayout.MAX_TITLE, kept[2].title?.length)
        assertEquals(ScreenLayout.MAX_LIMIT, kept[2].limit)
        assertEquals("no known part → the defaults", SectionCatalogue.ITEM_HEADER_PARTS,
            ScreenLayout.validate(listOf(LayoutSection("header", show = listOf("nope"))), LayoutScreen.ITEM_DETAIL)[0].show)
        assertEquals("menu belongs to places", emptyList<LayoutSection>(), ScreenLayout.validate(listOf(LayoutSection("menu")), LayoutScreen.ITEM_DETAIL))
    }

    @Test
    fun atMost24Sections() {
        val home = ScreenLayout.DEFAULTS[LayoutScreen.HOME]!!
        val many = (0 until 40).map { LayoutSection("type$it") } + home
        assertEquals(home.size, ScreenLayout.validate(many, LayoutScreen.HOME).size)
        val all = SectionCatalogue.types[LayoutScreen.HOME]!!.keys.sorted().map { LayoutSection(it) }
        assertTrue(ScreenLayout.validate(all + all, LayoutScreen.HOME).size <= ScreenLayout.MAX_SECTIONS)
    }

    @Test
    fun fallbackToDefaults() {
        val defaults = ScreenLayout.DEFAULTS[LayoutScreen.ITEM_DETAIL]!!
        assertEquals("no screen entry", defaults, ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, ScreenLayouts()))
        val unknownOnly = layouts("""{"catalogue": 1, "screens": {"item_detail": {"sections": [{"type": "hologram"}]}}}""")
        assertEquals("nothing known", defaults, ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, unknownOnly))
        val empty = layouts("""{"screens": {"item_detail": {"sections": []}}}""")
        assertEquals("empty list", defaults, ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, empty))
    }

    @Test
    fun serverOrderIsKept() {
        val l = layouts(
            """
            {"catalogue": 1, "screens": {"place_detail": {"sections": [
              {"type": "menu"}, {"type": "hours", "title": "Opening hours"}, {"type": "header", "show": ["image"]},
              {"type": "at_this_location"}]}}}
            """,
        )
        val sections = ScreenLayout.sections(LayoutScreen.PLACE_DETAIL, l)
        assertEquals(listOf("menu", "hours", "header", "at_this_location"), types(sections))
        assertEquals("Opening hours", sections[1].title)
        assertEquals(listOf("image"), sections[2].show)
    }

    // Overrides

    @Test
    fun categoryAndTagOverrides() {
        val l = layouts(
            """
            {"catalogue": 1, "screens": {
              "place_detail": {"sections": [{"type": "header"}, {"type": "about"}],
                "overrides": [{"match": "category:FOOD", "sections": [{"type": "menu"}, {"type": "hours"}, {"type": "header"}]}]},
              "item_detail": {"sections": [{"type": "header"}],
                "overrides": [{"match": "tag:nothing", "sections": [{"type": "hologram"}]},
                              {"match": "tag:live music", "sections": [{"type": "performers"}, {"type": "header"}]},
                              {"match": "category:Stage", "sections": [{"type": "place_link"}]}]}}}
            """,
        )
        val taco = ScreenFacts.subject(bundle.location(6)!!)
        assertEquals(listOf("menu", "hours", "header"), types(ScreenLayout.sections(LayoutScreen.PLACE_DETAIL, l, taco)))
        val stage = ScreenFacts.subject(bundle.location(1)!!)
        assertEquals(listOf("header", "about"), types(ScreenLayout.sections(LayoutScreen.PLACE_DETAIL, l, stage)))
        // The first matching override wins (tag before category here, in the server's order).
        val juniper = ScreenFacts.subject(bundle.scheduleItem(109)!!, bundle)
        assertEquals("Stage", juniper.category)
        assertEquals(listOf("performers", "header"), types(ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, l, juniper)))
        // An item at a stage without the tag: the category override.
        val item = bundle.scheduleItem(109)!!.copy(tags = listOf("folk"))
        assertEquals(listOf("place_link"), types(ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, l, ScreenFacts.subject(item, bundle))))
        // No category, no tag: the base.
        assertEquals(listOf("header"), types(ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, l, ScreenFacts.subject(bundle.scheduleItem(203)!!, bundle))))
    }

    @Test
    fun matchSyntax() {
        val s = ScreenLayout.Subject(category = "Food", tags = listOf("bbq"))
        assertTrue(ScreenLayout.matches("category:food", s))
        assertTrue(ScreenLayout.matches(" tag: BBQ ", s))
        assertFalse(ScreenLayout.matches("tag:", s))
        assertFalse(ScreenLayout.matches("bbq", s))
        assertFalse(ScreenLayout.matches("location:5", s))
    }

    // Conditions

    @Test
    fun conditions() {
        val facts = setOf("plan", "model_available", "during", "day:2026-11-14", "context.menu")
        assertTrue(ScreenLayout.holds("has_plan", facts))
        assertTrue(ScreenLayout.holds("model_available", facts))
        assertTrue(ScreenLayout.holds("has:context.menu", facts))
        assertFalse(ScreenLayout.holds("has:context.hours", facts))
        assertTrue(ScreenLayout.holds("day:2026-11-14", facts))
        assertFalse(ScreenLayout.holds("day:2026-11-15", facts))
        assertTrue(ScreenLayout.holds("not:after_end", facts))
        assertFalse(ScreenLayout.holds("not:during", facts))
        assertFalse("unknown → false", ScreenLayout.holds("phase_of_moon", facts))
        assertFalse("unknown stays false when negated", ScreenLayout.holds("not:phase_of_moon", facts))
        assertFalse("only catalogue facts", ScreenLayout.holds("has:secret", setOf("has:secret", "secret")))
        assertFalse(ScreenLayout.holds("day:tomorrow", setOf("day:tomorrow")))
    }

    @Test
    fun whenFiltersSections() {
        val l = layouts(
            """
            {"screens": {"home": {"sections": [
              {"type": "header"}, {"type": "countdown", "when": "before_start"}, {"type": "now_next", "when": ["during", "has:schedule"]},
              {"type": "about", "when": ["day:2026-11-15"]}, {"type": "updates", "when": ["sometime"]}]}}}
            """,
        )
        val facts = ScreenFacts.event(bundle, state)
        // Sat 10:00 AM is the event's start: during, not before it.
        assertTrue("during" in facts)
        assertFalse("before_start" in facts)
        assertTrue("day:2026-11-14" in facts)
        assertEquals(listOf("header", "now_next"), types(ScreenLayout.resolve(LayoutScreen.HOME, l, facts)))
    }

    // Facts

    @Test
    fun eventFacts() {
        val facts = ScreenFacts.event(bundle, state)
        for (fact in listOf("description", "schedule", "locations", "pins", "updates", "urgent", "coordinate")) assertTrue(fact, fact in facts)
        assertFalse("flyer" in facts)
        assertFalse("plan" in facts)
        assertFalse("likes" in facts)
        assertTrue("before_start" in ScreenFacts.event(bundle, state.copy(now = PlannerFixture.sat("08:00").toInstant())))
        val late = ScreenFacts.event(bundle, state.copy(now = Instant.parse("2026-11-16T12:00:00Z")))
        assertTrue("after_end" in late)
        assertFalse("during" in late)
        assertFalse("urgent only for 12 h", "urgent" in late)
    }

    @Test
    fun nowPlayingNeedsAPinnedPlace() {
        // Juniper Lane on the Main Stage.
        assertTrue("now_playing" in ScreenFacts.common(bundle, state.copy(now = PlannerFixture.sat("11:30").toInstant())))
        // Only Gates open, no place.
        val gates = state.copy(now = PlannerFixture.sat("10:15").toInstant())
        assertFalse("now_playing" in ScreenFacts.common(bundle, gates))
        assertEquals(listOf("selected_place"), types(ScreenLayout.resolve(LayoutScreen.MAP_OVERLAY, null, ScreenFacts.common(bundle, gates))))
    }

    @Test
    fun homeDefaults() {
        val s = state.copy(hasLikes = true, hasPlan = true)
        assertEquals(listOf("header", "urgent", "my_schedule", "map", "updates", "about"), types(ScreenLayout.resolve(LayoutScreen.HOME, null, ScreenFacts.event(bundle, s))))
        assertEquals(3, ScreenLayout.sections(LayoutScreen.HOME, null).first { it.type == "updates" }.limit)
        // Without anything in My schedule the section still shows (its empty state).
        assertTrue("my_schedule" in types(ScreenLayout.resolve(LayoutScreen.HOME, null, ScreenFacts.event(bundle, state))))
    }

    @Test
    fun olderHomeNamesMapToMySchedule() {
        val l = layouts("""{"screens": {"home": {"sections": [{"type": "plan_today"}, {"type": "liked", "title": "Faves"}, {"type": "map"}]}}}""")
        assertEquals("the website is always added at the end of Home", listOf("my_schedule", "map", "website"), types(ScreenLayout.sections(LayoutScreen.HOME, l)))
        assertEquals(2, SectionCatalogue.VERSION)
    }

    // Decoding

    @Test
    fun lenientDecoding() {
        val l = layouts(
            """
            {"catalogue": "x", "extra": 1, "screens": {
              "unknown_screen": {"sections": [{"type": "header"}]},
              "event_info": {"sections": [{"type": "faq", "limit": "many", "show": ["a", 3]}, {"no_type": true}, 7, {"type": "about"}],
                             "overrides": [{"sections": []}, {"match": "tag:x", "sections": [{"type": "faq"}]}]},
              "home": "broken"}}
            """,
        )
        assertEquals(0, l.catalogue)
        assertEquals(setOf(LayoutScreen.EVENT_INFO), l.screens.keys)
        val info = l.screens[LayoutScreen.EVENT_INFO]!!
        assertEquals(listOf("faq", "about"), types(info.sections))
        assertNull(info.sections[0].limit)
        assertEquals(listOf("a"), info.sections[0].show)
        assertEquals(listOf("tag:x"), info.overrides.map { it.match })
    }

    /** iOS decodes this as part of the bundle; on Android phase 1 keeps `bundle.layout` raw and this decodes it. */
    @Test
    fun bundleWithoutLayout() {
        assertNull(ScreenLayouts.decode(null))
        assertNull(ScreenLayouts.decode(JsonNull))
        assertNull(ScreenLayouts.decode(Json.parseToJsonElement("\"broken\"")))
        val withLayout = layouts("""{"catalogue": 1, "screens": {"home": {"sections": [{"type": "header"}]}}}""")
        assertEquals(1, withLayout.screens[LayoutScreen.HOME]?.sections?.size)
    }

    // Foldables: two columns and tabletop

    @Test
    fun detailColumnsFromTheContainerWidth() {
        val place = ScreenLayout.sections(LayoutScreen.PLACE_DETAIL, null)
        assertNull(DetailColumns.split(place, LayoutScreen.PLACE_DETAIL, 699.0))
        val wide = DetailColumns.split(place, LayoutScreen.PLACE_DETAIL, 700.0)!!
        assertEquals("header", wide.leading.first().type)
        assertTrue(wide.leading.all { it.type in DetailColumns.LEADING_TYPES[LayoutScreen.PLACE_DETAIL]!! })
        assertEquals(place.size, wide.leading.size + wide.trailing.size)
        assertTrue(wide.trailing.isNotEmpty())
        val item = DetailColumns.split(ScreenLayout.sections(LayoutScreen.ITEM_DETAIL, null), LayoutScreen.ITEM_DETAIL, 900.0)!!
        assertEquals("header", item.leading.first().type)
        // Screens without columns, or nothing for one side.
        assertNull(DetailColumns.split(ScreenLayout.sections(LayoutScreen.HOME, null), LayoutScreen.HOME, 900.0))
        assertNull(DetailColumns.split(listOf(LayoutSection("header")), LayoutScreen.PLACE_DETAIL, 900.0))
    }

    @Test
    fun homeTabletopHalves() {
        val halves = TabletopSplit.home(ScreenLayout.sections(LayoutScreen.HOME, null))!!
        assertEquals(listOf("header", "urgent", "countdown", "map"), types(halves.top))
        assertEquals("my_schedule", halves.bottom.first().type)
        assertNull(TabletopSplit.home(listOf(LayoutSection("header"))))
    }

    @Test
    fun aboutTextOnce() {
        val line = "Twenty food trucks, a beer garden and a band on Main Street."
        assertEquals(line, AboutText.merged(line, line))
        assertEquals(
            "twenty food trucks, a beer garden and a band on main street",
            AboutText.merged(line, "  twenty food trucks, a beer garden and a band on main street "),
        )
        assertEquals("$line Free entry.", AboutText.merged(line, "$line Free entry."))
        assertEquals("$line Dogs welcome.", AboutText.merged("$line Dogs welcome.", line))
        assertEquals(line, AboutText.merged("", line))
        assertEquals(line, AboutText.merged(line, null))
        assertEquals("$line\n\nRain or shine.", AboutText.merged("Rain or shine.", line))
    }

    // Catalogue 2 (API-027)

    @Test
    fun catalogue2NewScreensDecodeAndValidate() {
        val decoded = layouts(
            """
            {"screens": {
              "schedule_tab": {"sections": [{"type": "my_schedule"}, {"type": "featured", "ids": [101, 101, 114], "title": "Don't miss"}, {"type": "by_day"}, {"type": "hologram"}]},
              "places_tab": {"sections": [{"type": "by_category"}]},
              "updates_tab": {"sections": [{"type": "urgent"}, {"type": "latest"}, {"type": "earlier"}]},
              "onboarding": {"sections": [{"type": "welcome", "title": "Hi", "text": "  Welcome to the fair  ", "image": "http://x.test/a.png"}, {"type": "intro"}, {"type": "lock_screen"}]}
            }}
            """,
        )
        val schedule = ScreenLayout.sections(LayoutScreen.SCHEDULE_TAB, decoded)
        assertEquals(listOf("my_schedule", "featured", "by_day"), types(schedule))
        assertEquals(listOf(101, 114), schedule[1].ids)
        val onboarding = ScreenLayout.sections(LayoutScreen.ONBOARDING, decoded)
        assertEquals(listOf("welcome", "intro", "lock_screen"), types(onboarding))
        assertEquals("Welcome to the fair", onboarding[0].text)
        assertNull("https only", onboarding[0].image)
        // Untouched screens have no built-in layout: today's tabs.
        assertTrue(ScreenLayout.sections(LayoutScreen.SCHEDULE_TAB, null).isEmpty())
        assertNull(TabListLayout.schedule(emptyList()))
        assertNull(OnboardingLayout.of(emptyList()))
    }

    @Test
    fun tabListLayout() {
        val schedule = TabListLayout.schedule(listOf(LayoutSection("featured", title = "Picks", ids = listOf(3)), LayoutSection("by_place")))
        assertEquals("by_location", schedule?.style)
        assertEquals(false, schedule?.showsMySchedule)
        assertEquals(listOf(3), schedule?.featured)
        assertEquals("Picks", schedule?.featuredTitle)
        assertEquals("now_next", TabListLayout.schedule(listOf(LayoutSection("my_schedule"), LayoutSection("up_next")))?.style)
        assertEquals("cards", TabListLayout.places(listOf(LayoutSection("all_places", show = listOf("image"))))?.style)
        assertEquals("list", TabListLayout.places(listOf(LayoutSection("nearby")))?.style)
        assertEquals("grouped", TabListLayout.places(listOf(LayoutSection("by_category")))?.style)
    }

    @Test
    fun updatesLayoutEachOnceNewestFirst() {
        val now = Instant.ofEpochSecond(1_000_000)
        fun at(seconds: Long) = now.plusSeconds(seconds).atOffset(ZoneOffset.UTC)
        val notifications = listOf(
            EventNotification(1, "Old", "", at(-3 * 86400)),
            EventNotification(2, "Storm", "", at(-600), NotificationPriority.URGENT),
            EventNotification(3, "Gates", "", at(-3600)),
            EventNotification(4, "Pinned", "", at(-2 * 86400)),
        )
        val groups = UpdatesLayout.groups(
            listOf(LayoutSection("urgent"), LayoutSection("pinned", ids = listOf(4)), LayoutSection("latest")), notifications, now,
        )
        assertEquals(listOf("urgent", "pinned", "latest", "earlier"), groups.map { it.type })
        assertEquals(listOf(listOf(2), listOf(4), listOf(3), listOf(1)), groups.map { it.ids })
        assertTrue(UpdatesLayout.groups(listOf(LayoutSection("faq")), notifications, now).all { it.ids.isEmpty() })
    }

    @Test
    fun onboardingLayout() {
        val layout = OnboardingLayout.of(listOf(LayoutSection("welcome"), LayoutSection("location"), LayoutSection("plan")))
        assertEquals("a welcome card with nothing to say is skipped", listOf(OnboardingLayout.Card.LOCATION, OnboardingLayout.Card.PLAN), layout?.cards)
        val welcome = OnboardingLayout.of(listOf(LayoutSection("welcome", text = "Hello"), LayoutSection("notifications")))
        assertEquals(listOf(OnboardingLayout.Card.WELCOME, OnboardingLayout.Card.NOTIFICATIONS), welcome?.cards)
        assertEquals("Hello", welcome?.welcomeText)
    }

    @Test
    fun homeColumnsBalancedAndInOrder() {
        val sections = listOf("my_schedule", "map", "updates", "faq", "getting_there", "about", "website").map { LayoutSection(it) }
        val columns = HomeColumns.split(sections)
        assertEquals(listOf("my_schedule", "updates", "getting_there", "website"), types(columns.leading))
        assertEquals(listOf("map", "faq", "about"), types(columns.trailing))
        assertEquals(sections.size, columns.leading.size + columns.trailing.size)
        assertTrue(HomeColumns.split(emptyList()).leading.isEmpty())
    }

    @Test
    fun foldRules() {
        assertEquals("map", FoldRules.primary(FoldRules.Screen.MAP))
        assertEquals("chat", FoldRules.primary(FoldRules.Screen.CHAT))
        assertEquals(FoldRules.Folded.PushedDetail(12), FoldRules.folded(FoldRules.Screen.SCHEDULE, 12))
        assertEquals(FoldRules.Folded.ListPane, FoldRules.folded(FoldRules.Screen.PLACES, null))
        assertEquals(FoldRules.Folded.Map(selectedPin = 5), FoldRules.folded(FoldRules.Screen.MAP, 5))
        assertEquals(FoldRules.Folded.Same, FoldRules.folded(FoldRules.Screen.HOME, null))
        assertNotNull(FoldRules.primary(FoldRules.Screen.DETAIL))
    }
}
