package com.arcu.attnd.unit

import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.domain.PlanPace
import com.arcu.attnd.domain.PlanPreferences
import com.arcu.attnd.domain.PlanRequest
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Shared spec §6–7: reading the model's preferences, the plain-rule reading, and example requests. */
class PlanRequestTest {

    private val saturday = LocalDate.of(2026, 11, 14)
    private val sunday = LocalDate.of(2026, 11, 15)

    @Test
    fun parseValidatesEverythingAgainstTheBundle() {
        val raw = """Sure! {"liked_tags":["Live Music","bbq","opera"],"disliked_tags":["dj"],
            "liked_categories":["food","Rides"],"similar_to":[101,"S103",9999,"x"],"days":["2026-11-15","2026-12-01"],
            "earliest":"15:00","latest":"25:00","hours":2.5,"pace":"Packed","include_meals":true,"kid_friendly":"true","extra":1}
            Have fun."""
        val prefs = PlanRequest.parse(raw, bundle, now)!!
        assertEquals(setOf("live music", "bbq"), prefs.likedTags)
        assertEquals(setOf("dj"), prefs.dislikedTags)
        assertEquals(setOf("Food"), prefs.likedCategories)
        assertEquals(setOf(101, 103), prefs.similarTo)
        assertEquals(listOf(sunday), prefs.days)
        assertEquals(LocalTime.of(15, 0), prefs.earliest)
        assertNull(prefs.latest)
        assertEquals(150, prefs.durationMinutes)
        assertEquals(PlanPace.PACKED, prefs.pace)
        assertTrue(prefs.includeMeals)
        assertTrue(prefs.kidFriendly)
    }

    @Test
    fun parseRejectsWhatIsNotJson() {
        assertNull(PlanRequest.parse("I can't help with that.", bundle, now))
        assertNull(PlanRequest.parse("{broken", bundle, now))
        assertEquals(PlanPreferences(), PlanRequest.parse("{}", bundle, now))
    }

    @Test
    fun heuristicReadsTheRequest() {
        val prefs = PlanRequest.heuristic("Plan my Saturday around live music and BBQ, no DJ please, after 3pm with dinner", bundle, now)
        assertEquals(setOf("live music", "bbq"), prefs.likedTags)
        assertEquals(setOf("dj"), prefs.dislikedTags)
        assertEquals(listOf(saturday), prefs.days)
        assertEquals(LocalTime.of(15, 0), prefs.earliest)
        assertTrue(prefs.includeMeals)
        assertFalse(prefs.kidFriendly)

        val short = PlanRequest.heuristic("I've got 3 hours, show me the highlights", bundle, now)
        assertEquals(180, short.durationMinutes)
        assertEquals(PlanPreferences(durationMinutes = 180), short)

        val kids = PlanRequest.heuristic("Kid-friendly afternoon, nice and relaxed", bundle, now)
        assertTrue(kids.kidFriendly)
        assertEquals(setOf("kid-friendly"), kids.likedTags)
        assertEquals(LocalTime.of(12, 0), kids.earliest)
        assertEquals(LocalTime.of(17, 0), kids.latest)
        assertEquals(PlanPace.RELAXED, kids.pace)

        val named = PlanRequest.heuristic("More like Big Sky Radio tomorrow until 2", bundle, now)
        assertEquals(setOf(103), named.similarTo)
        assertEquals(listOf(sunday), named.days)
        assertEquals(LocalTime.of(14, 0), named.latest)
    }

    @Test
    fun mergeFillsWhatTheModelLeftOut() {
        val model = PlanPreferences(likedTags = setOf("country", "dj"), pace = PlanPace.BALANCED)
        val rules = PlanPreferences(likedTags = setOf("bbq"), dislikedTags = setOf("dj"), days = listOf(saturday), pace = PlanPace.RELAXED, includeMeals = true)
        val merged = PlanRequest.merge(model, rules)
        assertEquals(setOf("country", "bbq"), merged.likedTags)
        assertEquals(setOf("dj"), merged.dislikedTags)
        assertEquals(listOf(saturday), merged.days)
        assertEquals(PlanPace.RELAXED, merged.pace)
        assertTrue(merged.includeMeals)
        assertEquals(rules, PlanRequest.merge(null, rules))
    }

    @Test
    fun planRequestsAreRecognised() {
        assertTrue(PlanRequest.looksLikePlan("Plan my Saturday around live music and BBQ"))
        assertTrue(PlanRequest.looksLikePlan("I've got 3 hours, show me the highlights"))
        assertTrue(PlanRequest.looksLikePlan("Kid-friendly afternoon"))
        assertTrue(PlanRequest.looksLikePlan("make an itinerary"))
        assertFalse(PlanRequest.looksLikePlan("What's on near me right now?"))
        assertFalse(PlanRequest.looksLikePlan("Where can I get coffee?"))
    }

    @Test
    fun examplesComeFromTheEvent() {
        assertEquals(
            listOf(
                PlanExample("Plan my Saturday around live music and food", isPlan = true),
                PlanExample("I've got 3 hours, show me the highlights", isPlan = true),
                PlanExample("Kid-friendly afternoon", isPlan = true),
                PlanExample("What's on near me right now?", isPlan = false),
            ),
            PlanRequest.examples(bundle, now),
        )
        val noFamily = bundle.copy(schedule = bundle.schedule.filter { 108 != it.id })
        assertFalse(PlanRequest.examples(noFamily, now).any { it.text == "Kid-friendly afternoon" })
    }

    @Test
    fun promptListsTheVocabularyAndTheRequest() {
        val prompt = PlanRequest.prompt(bundle, "Plan my Saturday", hearts, now)
        assertTrue(prompt.contains("DAYS: 2026-11-14 Saturday, 2026-11-15 Sunday"))
        assertTrue(prompt.contains("TAGS: live music, "))
        assertTrue(prompt.contains("CATEGORIES: Stage, Food, Family, Drinks, Services"))
        assertTrue(prompt.contains("HEARTED: 101 The Prairie Lights; 114 Neon Armadillo"))
        assertTrue(prompt.contains("NOW: Saturday 2026-11-14 10:00"))
        assertTrue(prompt.trimEnd().endsWith("REQUEST: Plan my Saturday\nJSON:"))
    }
}
