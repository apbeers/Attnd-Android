package com.arcu.attnd.unit

import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.BlockContext
import com.arcu.attnd.domain.BlockJson
import com.arcu.attnd.domain.BlockValidator
import com.arcu.attnd.domain.Component
import com.arcu.attnd.domain.ComponentCatalogue
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.LocationContext
import com.arcu.attnd.domain.MenuEntry
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SectionCatalogue
import com.arcu.attnd.unit.PlannerFixture.bundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One component catalogue (`Component.kt`): server layouts and chat components decode to the same `Component`; older
 * names map across; unknown types are skipped. iOS `ComponentTests` cases 1–3; its `show` tool / token budget cases
 * belong to phase 6a (`AssistantModel`) and the API-023 theme decoding to phase 7.
 */
class ComponentTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun layoutsAndChatDecodeTheSameComponent() {
        val fromLayout = ComponentCatalogue.component(LayoutSection("menu", title = "Food"), subject = EntityRef(RefKind.LOCATION, 6))
        val fromChat = ComponentCatalogue.decode(obj("""{"type":"menu","ids":["l6"],"title":"Food"}"""))
        assertEquals(fromLayout, fromChat)
        // Event-wide parts carry no subject either way.
        assertEquals(ComponentCatalogue.component(LayoutSection("faq", limit = 3)), ComponentCatalogue.decode(obj("""{"type":"faq","limit":3}""")))
        // Older names map across; a subject of the wrong kind or none is refused.
        assertEquals("my_schedule", ComponentCatalogue.component(LayoutSection("liked")).type)
        assertEquals("my_schedule", ComponentCatalogue.chat("plan_today")?.type)
        assertNull(ComponentCatalogue.chat("menu", ids = listOf(EntityRef(RefKind.SCHEDULE, 101))))
        assertNull(ComponentCatalogue.chat("walk_map"))
        assertNull(ComponentCatalogue.chat("weather"))
    }

    @Test
    fun blockJsonCarriesScreenComponents() {
        // A screen component in a chat answer is a block; unknown types are dropped.
        val decoded = BlockJson.decode(Json.parseToJsonElement("""{"type":"performers","ids":["s101"]}"""))
        val c = ((decoded as BlockJson.Decoded.Ok).block as AssistantBlock.ComponentBlock).component
        assertEquals("performers", c.type)
        assertEquals("""{"type":"performers","ids":["s101"]}""", BlockJson.encode(AssistantBlock.ComponentBlock(c)).toString())
        assertEquals(BlockJson.Decoded.Unknown, BlockJson.decode(Json.parseToJsonElement("""{"type":"calendar"}""")))
        // The validator keeps a component only when its data is there (no menu in the fixture).
        val ctx = BlockContext(bundle, null, PlannerFixture.now)
        val menu = AssistantBlock.ComponentBlock(Component("menu", listOf(EntityRef(RefKind.LOCATION, 6))))
        assertTrue(BlockValidator.validate(listOf(menu), ctx).isEmpty())
        assertEquals(1, BlockValidator.validate(listOf(AssistantBlock.ComponentBlock(Component("my_schedule"))), ctx).size)
        // Android: with the place's menu in its context the menu stays.
        val withMenu = bundle.copy(locations = bundle.locations.map { if (it.id == 6) it.copy(context = LocationContext(menu = listOf(MenuEntry("Al pastor")))) else it })
        assertEquals(1, BlockValidator.validate(listOf(menu), ctx.copy(bundle = withMenu)).size)
        // The renderer's input from a block keeps its ids.
        assertEquals(listOf("l6"), ComponentCatalogue.component(AssistantBlock.Carousel(listOf(EntityRef(RefKind.LOCATION, 6)))).ids.map { it.code })
    }

    @Test
    fun catalogueIsTheLayoutSide() {
        assertNotNull(SectionCatalogue.type("my_schedule", LayoutScreen.HOME))
        assertNotNull(SectionCatalogue.type("liked", LayoutScreen.HOME))
        assertNull(SectionCatalogue.type("my_schedule", LayoutScreen.PLACE_DETAIL))
        assertEquals(SectionCatalogue.PLACE_HEADER_PARTS, SectionCatalogue.type("header", LayoutScreen.PLACE_DETAIL)?.parts)
        assertTrue(ComponentCatalogue.chatScreenTypes.containsAll(listOf("menu", "performers", "faq", "getting_there", "my_schedule", "amenities")))
    }
}
