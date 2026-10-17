package com.sheltr.shared.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HazardVocabularyTest {

    @Test
    fun termsCoverEveryDisasterTypeWord() {
        assertTrue(
            HazardVocabulary.TERMS.containsAll(
                listOf("earthquake", "flood", "tropical", "cyclone", "tornado", "tsunami", "wildfire", "drought"),
            ),
        )
    }

    @Test
    fun synonymsMapToATerm() {
        assertEquals("earthquake", HazardVocabulary.SYNONYMS["quake"])
        assertEquals("tornado", HazardVocabulary.SYNONYMS["twister"])
        assertTrue(HazardVocabulary.SYNONYMS["hurricane"] in HazardVocabulary.TERMS)
        assertTrue(HazardVocabulary.SYNONYMS["typhoon"] in HazardVocabulary.TERMS)
    }
}
