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
}
