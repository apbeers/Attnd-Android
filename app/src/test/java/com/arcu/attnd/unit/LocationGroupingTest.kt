package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.LocationGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationGroupingTest {

    private fun location(id: Int, name: String, category: String?) = EventLocation(
        id = id, name = name, snippet = "", description = "", category = category, website = null,
        imageUrl = null, address = "", coordinate = null, sortOrder = id,
    )

    @Test
    fun byCategory_followsBundleCategoryOrder_thenExtras_thenOther() {
        val locations = listOf(
            location(1, "Stage A", "Stage"),
            location(2, "Restroom", null),
            location(3, "Tacos", "Food"),
            location(4, "Merch", "Shops"),
            location(5, "Stage B", "Stage"),
        )
        val sections = LocationGrouping.byCategory(locations, categories = listOf("Food", "Stage"))
        assertEquals(listOf("Food", "Stage", "Shops", LocationGrouping.UNCATEGORIZED_TITLE), sections.map { it.title })
        assertEquals(listOf(1, 5), sections[1].locations.map { it.id })
        assertEquals(listOf(2), sections.last().locations.map { it.id })
    }

    @Test
    fun filter_byQueryAndCategory() {
        val locations = listOf(location(1, "Taco Truck", "Food"), location(2, "Main Stage", "Stage"))
        assertEquals(listOf(1), LocationGrouping.filter(locations, "taco", null).map { it.id })
        assertEquals(listOf(2), LocationGrouping.filter(locations, "", "Stage").map { it.id })
        assertEquals(emptyList<Int>(), LocationGrouping.filter(locations, "taco", "Stage").map { it.id })
        assertEquals(listOf(1, 2), LocationGrouping.filter(locations, "", null).map { it.id })
    }

    @Test
    fun mappable_skipsLocationsWithoutRealCoordinates() {
        val bundle = ApiMapper.parseBundle(SampleBundles.FULL)!!
        assertEquals(listOf(5), LocationGrouping.mappable(bundle.locations).map { it.first.id })
        assertEquals(listOf("Food", "Stage"), LocationGrouping.availableCategories(bundle.locations, bundle.categories))
    }
}
