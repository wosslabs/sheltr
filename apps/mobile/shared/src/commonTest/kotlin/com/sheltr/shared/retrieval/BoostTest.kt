package com.sheltr.shared.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals

/** Country cases ported from the server's CountryBoostTest; hazard cases from HazardBoostTest (active hazards only). */
class BoostTest {

    @Test
    fun matchingCountryIsBoosted() {
        assertEquals(1.15, Boost.country("GB", "GB"), 1e-9)
    }

    @Test
    fun globalAndOtherCountryPassagesAreNotBoosted() {
        assertEquals(1.0, Boost.country(null, "GB"))
        assertEquals(1.0, Boost.country("US", "GB"))
    }

    @Test
    fun noQueryCountryMeansNoBoost() {
        assertEquals(1.0, Boost.country("GB", null))
    }

    @Test
    fun boostsADocumentAboutAnActiveHazard() {
        assertEquals(1.15, Boost.hazard(listOf("FLOOD", "STORM"), setOf("FLOOD")), 1e-9)
    }

    @Test
    fun leavesOtherDocumentsAndQueriesWithoutHazardsUnchanged() {
        assertEquals(1.0, Boost.hazard(listOf("WILDFIRE"), setOf("FLOOD")))
        assertEquals(1.0, Boost.hazard(listOf("FLOOD"), emptySet()))
        assertEquals(1.0, Boost.hazard(emptyList(), setOf("FLOOD")))
    }
}
