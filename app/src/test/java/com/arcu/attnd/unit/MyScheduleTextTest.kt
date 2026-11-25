package com.arcu.attnd.unit

import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.MyScheduleText
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.unit.PlannerFixture.sat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The words and small rules of My schedule's UI (phase 3b; iOS `MyScheduleText`, `HeartButton`,
 * `ScheduleTimeDialog`, `OrganizerChangeRow`): no "like" wording, the reminder menu, "When?", the Undo
 * snackbar, organizer changes, Home's saved places row.
 */
class MyScheduleTextTest {
    private val zone = AssistantFixture.zone
    private val bundle = PlannerFixture.bundle

    @Test
    fun heartAndMenuWords() {
        assertEquals("Add Main Stage to my schedule", MyScheduleText.heartDescription("Main Stage", added = false))
        assertEquals("Main Stage, In my schedule", MyScheduleText.heartDescription("Main Stage", added = true))
        assertEquals("Add to my schedule", MyScheduleText.heartDescription(null, added = false))
        assertEquals("In my schedule", MyScheduleText.heartDescription(null, added = true))
        assertEquals("Set a Time…", MyScheduleText.timeAction(hasTime = false))
        assertEquals("Change Time…", MyScheduleText.timeAction(hasTime = true))
        assertEquals("Just Save It", MyScheduleText.saveWithoutTime(hasTime = false))
        assertEquals("No Time, Just Save It", MyScheduleText.saveWithoutTime(hasTime = true))
        assertEquals("Remove from My Schedule", MyScheduleText.REMOVE)
        val words = listOf(MyScheduleText.ADD_LONG, MyScheduleText.IN_SCHEDULE, MyScheduleText.REMOVE, MyScheduleText.SAVED_PLACES, MyScheduleText.EMPTY, MyScheduleText.NOTHING_YET)
        assertTrue(words.none { "like" in it.lowercase() })
    }

    @Test
    fun reminderChoices() {
        // iOS: 5 / 10 / 15 / 30 min / 1 hour / Off, default 15.
        assertEquals(listOf("5 min before", "10 min before", "15 min before", "30 min before", "1 hour before", "Off"), MySchedule.REMINDER_CHOICES.map(MyScheduleText::reminder))
        val s = MySchedule()
        s.add(ScheduleTarget.item(101))
        assertEquals(15, s.entry(ScheduleTarget.item(101))!!.reminderMinutes)
        assertNull(s.setReminder(ScheduleTarget.item(101), 15)?.metric)
        s.setReminder(ScheduleTarget.item(101), 0)
        assertEquals(0, s.entry(ScheduleTarget.item(101))!!.reminderMinutes)
    }

    @Test
    fun whenOptionsMarkTheSuggestedTime() {
        val s = MySchedule()
        s.add(ScheduleTarget.item(113)) // Sundown Choir 18:15–18:45
        val options = s.suggestedTimes(6, bundle, sat("17:52").toInstant(), walkMinutes = 3)
        val labels = options.times.map { MyScheduleText.timeOption(it, options.suggested, zone, Locale.US) }
        assertEquals("6:00 PM", labels[0])
        assertEquals("7:00 PM (Suggested)", labels[2])
        assertEquals(1, labels.count { it.endsWith("(Suggested)") })
    }

    @Test
    fun walkMinutesOnlyWithinWalkingRange() {
        val here = GeoPoint(32.7555, -97.3308)
        assertNull(MyScheduleText.walkMinutes(null, here))
        assertNull(MyScheduleText.walkMinutes(here, null))
        // ~1 km: 1,000 × 1.2 ÷ 1.3 m/s = 923 s → 16 min (rounded up).
        assertEquals(16, MyScheduleText.walkMinutes(here, GeoPoint(32.7645, -97.3308)))
        // ~20 km: out of range.
        assertNull(MyScheduleText.walkMinutes(here, GeoPoint(32.9355, -97.3308)))
    }

    @Test
    fun undoAndOrganizerTexts() {
        assertEquals("Taco Row added to My Schedule", MyScheduleText.added("Taco Row"))
        assertEquals("It added to My Schedule", MyScheduleText.added(null))
        assertEquals("Taco Row removed from My Schedule", MyScheduleText.removed("Taco Row"))
        val moved = OrganizerChange(105, "Velvet Mesquite", OrganizerChange.Kind.Moved(sat("20:30").toInstant()))
        assertEquals("Velvet Mesquite moved to 8:30 PM.", MyScheduleText.organizerChange(moved, zone, Locale.US))
        assertEquals("Velvet Mesquite changed.", MyScheduleText.organizerChange(moved.copy(kind = OrganizerChange.Kind.Changed), zone))
        assertEquals("Velvet Mesquite was cancelled.", MyScheduleText.organizerChange(moved.copy(kind = OrganizerChange.Kind.Cancelled), zone))
        assertEquals("1 saved place", MyScheduleText.savedPlaces(1))
        assertEquals("3 saved places", MyScheduleText.savedPlaces(3))
    }
}
