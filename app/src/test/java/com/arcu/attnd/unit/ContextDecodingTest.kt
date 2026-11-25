package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.ContextMapper
import com.arcu.attnd.domain.AmenityKind
import com.arcu.attnd.domain.AppleMusicLinks
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.FaqEntry
import com.arcu.attnd.domain.MenuEntry
import com.arcu.attnd.domain.ScheduleItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * API-018 rich context (`event.context`, `locations[].context`, `schedule[].context`): lenient
 * decoding exactly as the contract says — every key optional, unknown keys ignored, lossy lists,
 * https-only links, unknown amenity kinds → other, empty objects → null. The decoding cases of
 * iOS `ContextDecodingTests` (its search case belongs to the assistant phase).
 */
class ContextDecodingTest {

    private fun bundle(json: String): EventBundle = ApiMapper.parseBundle(json)!!

    private fun event(eventJson: String): Event = bundle("""{"event":$eventJson}""").event

    private fun location(json: String): EventLocation? = bundle("""{"event":{"id":9},"locations":[$json]}""").locations.firstOrNull()

    private fun item(json: String): ScheduleItem? = bundle("""{"event":{"id":9},"schedule":[$json]}""").schedule.firstOrNull()

    @Test
    fun eventContextFull() {
        val event = event(
            """
            {"id":1,"name":"Fair","event_code":"FAIR","context":{
              "about":" A two-day fair. ",
              "faq":[{"q":"Bags?","a":"Clear bags only."},{"q":"","a":"dropped"},{"q":"Re-entry?"},"junk",{"q":"Pets?","a":"Service animals only.","x":1}],
              "amenities":[
                {"kind":"restrooms","name":"Restrooms","location_id":3,"note":"By the gate"},
                {"kind":"first_aid","name":"","location_id":null},
                {"kind":"teleporter","name":"Beam me up"},
                {"name":"","note":"nothing"},
                {"kind":"ACCESSIBLE_ENTRANCE","name":"Ramp","location_id":"7"}
              ],
              "accessibility":"Step-free paths.","policies":"No glass.","getting_there":"Bus 12.",
              "weather_plan":"Rain or shine.",
              "links":[{"title":"Bag policy","url":"https://fair.example/bags"},{"title":"Old","url":"http://fair.example"},
                       {"url":"https://fair.example/map"},{"title":"Relative","url":"/x"}],
              "future":{"a":1}
            }}
            """.trimIndent(),
        )
        val context = event.context!!
        assertEquals("A two-day fair.", context.about)
        assertEquals(listOf(FaqEntry("Bags?", "Clear bags only."), FaqEntry("Pets?", "Service animals only.")), context.faq)
        assertEquals(
            listOf(AmenityKind.RESTROOMS, AmenityKind.FIRST_AID, AmenityKind.OTHER, AmenityKind.ACCESSIBLE_ENTRANCE),
            context.amenities.map { it.kind },
        )
        assertEquals(listOf("Restrooms", "First aid", "Beam me up", "Ramp"), context.amenities.map { it.name })
        assertEquals(3, context.amenities[0].locationId)
        assertEquals("By the gate", context.amenities[0].note)
        assertNull(context.amenities[1].locationId)
        assertNull("a string id is not an id", context.amenities[3].locationId)
        assertEquals("Step-free paths.", context.accessibility)
        assertEquals("No glass.", context.policies)
        assertEquals("Bus 12.", context.gettingThere)
        assertEquals("Rain or shine.", context.weatherPlan)
        assertEquals(listOf("Bag policy", "fair.example"), context.links.map { it.title })
        assertEquals(listOf("https://fair.example/bags", "https://fair.example/map"), context.links.map { it.url })
    }

    @Test
    fun missingNullOrEmptyContextIsNull() {
        assertNull(event("""{"id":1}""").context)
        assertNull(event("""{"id":1,"context":null}""").context)
        assertNull(event("""{"id":1,"context":{}}""").context)
        assertNull(event("""{"id":1,"context":{"about":"  ","faq":[]}}""").context)
        assertNull("wrong type: ignored", event("""{"id":1,"context":"text"}""").context)
        assertNull(location("""{"id":1,"context":[]}""")!!.context)
        assertNull(item("""{"id":1,"context":{"performers":[{"bio":"no name"}]}}""")!!.context)
    }

    @Test
    fun locationContext() {
        val location = location(
            """
            {"id":4,"name":"BBQ Pit","context":{"about":"Smoked daily.",
              "menu":[{"name":"Brisket plate","price":"$14","note":"Gluten-free"},{"name":"Rib","price":12.5},{"price":"$3"},{"name":"Tea"}],
              "hours":"11 AM – 9 PM","accessibility":"Step-free.","age_limit":"All ages",
              "links":[{"title":"Menu","url":"https://bbq.example/menu"}]}}
            """.trimIndent(),
        )!!
        val context = location.context!!
        assertEquals("Smoked daily.", context.about)
        assertEquals(
            listOf(MenuEntry("Brisket plate", "$14", "Gluten-free"), MenuEntry("Rib", "12.5"), MenuEntry("Tea")),
            context.menu,
        )
        assertEquals("11 AM – 9 PM", context.hours)
        assertEquals("Step-free.", context.accessibility)
        assertEquals("All ages", context.ageLimit)
        assertEquals(1, context.links.size)
    }

    @Test
    fun scheduleItemContext() {
        val item = item(
            """
            {"id":101,"name":"The Prairie Lights","context":{"about":"Headliners.",
              "performers":[{"name":"The Prairie Lights","bio":"Texas country band.","genres":["country"," Texas swing ","",3]},
                            {"name":"Guest","genres":"rock"},{"name":"  "},{"name":"DJ Two","genres":["Country","dj"]}],
              "age_limit":"21+","price":"Free","accessibility":"ASL interpreted.",
              "links":[{"title":"Band","url":"https://band.example"}]}}
            """.trimIndent(),
        )!!
        val context = item.context!!
        assertEquals("Headliners.", context.about)
        assertEquals(listOf("The Prairie Lights", "Guest", "DJ Two"), context.performers.map { it.name })
        assertEquals(listOf("country", "Texas swing"), context.performers[0].genres)
        assertEquals("not a list", emptyList<String>(), context.performers[1].genres)
        assertEquals("distinct, case-insensitive", listOf("country", "Texas swing", "dj"), context.genres)
        assertEquals("21+", context.ageLimit)
        assertEquals("Free", context.price)
        assertEquals("ASL interpreted.", context.accessibility)
        assertEquals("Band", context.links.first().title)
    }

    /** API-030: `apple_music_url` only as an https Apple Music link; anything else is ignored. */
    @Test
    fun performerAppleMusicUrl() {
        val performers = Json.parseToJsonElement(
            """
            [{"name":"Juniper Lane","apple_music_url":"https://music.apple.com/us/artist/juniper-lane/123"},
             {"name":"Old Link","apple_music_url":"http://music.apple.com/us/artist/x/1"},
             {"name":"Elsewhere","apple_music_url":"https://open.example.com/artist/1"},
             {"name":"Blank","apple_music_url":""},
             {"name":"Number","apple_music_url":42},
             {"name":"None"}]
            """.trimIndent(),
        ) as JsonArray
        assertEquals(
            listOf("https://music.apple.com/us/artist/juniper-lane/123", null, null, null, null, null),
            performers.map { ContextMapper.performer(it)!!.appleMusicUrl },
        )
        assertNull(AppleMusicLinks.of("https://music.apple.com.evil.example/x"))
        assertNotNull(AppleMusicLinks.of("https://beta.music.apple.com/x"))
    }

    @Test
    fun bundleWithContextRoundTripsFromRawJson() {
        // The offline copy stores the raw bundle JSON, so context comes back from disk unchanged.
        val bundle = bundle(
            """
            {"event":{"id":1,"name":"Fair","context":{"about":"Hi"}},
             "locations":[{"id":2,"name":"Stage","context":{"hours":"All day"}}],
             "schedule":[{"id":3,"name":"Show","context":{"price":"$5"}}]}
            """.trimIndent(),
        )
        assertEquals("Hi", bundle.event.context?.about)
        assertEquals("All day", bundle.locations.first().context?.hours)
        assertEquals("$5", bundle.schedule.first().context?.price)
    }
}
