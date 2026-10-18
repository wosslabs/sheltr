package com.sheltr.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit (no Robolectric): [HomeViewModel] touches no Android framework class beyond resource-id
 * references (ints), so it's testable without an Android runtime.
 */
class HomeViewModelTest {

    /** Same 12 names as `tools/publisher`'s `DisasterType` enum -- the shared source of truth for hazard names. */
    private val expectedHazardNames = setOf(
        "EARTHQUAKE",
        "FLOOD",
        "TROPICAL_CYCLONE",
        "TORNADO",
        "TSUNAMI",
        "WILDFIRE",
        "EXTREME_HEAT",
        "WINTER_STORM",
        "LANDSLIDE",
        "GLACIAL_LAKE_FLOOD",
        "VOLCANO",
        "DROUGHT",
    )

    @Test
    fun hazardTilesCoverAllTwelveDisasterTypes() {
        assertEquals(12, HomeViewModel().hazardTiles.size)
        assertTrue(HomeViewModel().hazardTiles.any { it.hazard == "EARTHQUAKE" })
    }

    @Test
    fun hazardTilesMatchTheDisasterTypeVocabularyExactly() {
        val actual = HomeViewModel().hazardTiles.map { it.hazard }.toSet()
        assertEquals(expectedHazardNames, actual)
    }

    @Test
    fun hazardTilesHaveNoDuplicateHazardNames() {
        val names = HomeViewModel().hazardTiles.map { it.hazard }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun blankSearchSubmitDoesNothing() {
        var called = false
        val vm = HomeViewModel()
        vm.onSearchSubmit("   ") { called = true }
        assertFalse(called)
    }

    @Test
    fun nonBlankSearchSubmitNavigatesWithTrimmedText() {
        var navigatedWith: String? = null
        val vm = HomeViewModel()
        vm.onSearchSubmit("  flashlight batteries  ") { navigatedWith = it }
        assertEquals("flashlight batteries", navigatedWith)
    }

    @Test
    fun hazardTappedPassesHazardNameThrough() {
        var navigatedWith: String? = null
        val vm = HomeViewModel()
        vm.onHazardTapped("EARTHQUAKE") { navigatedWith = it }
        assertEquals("EARTHQUAKE", navigatedWith)
    }
}
