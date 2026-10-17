package com.sheltr.shared.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryExpansionTest {

    @Test
    fun expandAppendsCanonicalTermForSynonym() =
        assertEquals("quake near the river earthquake", expandWithSynonyms("quake near the river"))

    @Test
    fun expandIsNoOpWithoutASynonym() =
        assertEquals("water near the river", expandWithSynonyms("water near the river"))

    @Test
    fun expandDoesNotDuplicateAnAlreadyPresentCanonicalTerm() =
        assertEquals("earthquake quake", expandWithSynonyms("earthquake quake"))

    @Test
    fun expandAppendsEachCanonicalTermOnlyOnce() =
        assertEquals("quake quake earthquake", expandWithSynonyms("quake quake"))

    @Test
    fun correctTyposFixesASingleTypo() =
        assertEquals("earthquake shelter", correctTypos("eartquake shelter"))

    @Test
    fun correctTyposLeavesCorrectlySpelledWordsAlone() =
        assertEquals("water shelter", correctTypos("water shelter"))

    @Test
    fun correctTyposLeavesExactVocabularyWordsAlone() =
        assertEquals("earthquake", correctTypos("earthquake"))

    @Test
    fun correctTyposLeavesWordsShorterThanFourLettersAlone() =
        assertEquals("lak", correctTypos("lak"))

    @Test
    fun correctTyposLeavesUnrelatedMisspellingsAlone() =
        assertEquals("zzzzz", correctTypos("zzzzz"))

    @Test
    fun correctTyposLeavesCommonEnglishWordsAlone() {
        // These are all distance 1 from a short, generic TERMS entry (food/head/.../winner below are each one edit
        // away from flood/heat/lake/storm/drought/winter respectively). None of them must be rewritten.
        for (word in listOf("food", "head", "like", "make", "blood", "floor", "store", "brought", "winner")) {
            assertEquals(word, correctTypos(word))
        }
    }
}
