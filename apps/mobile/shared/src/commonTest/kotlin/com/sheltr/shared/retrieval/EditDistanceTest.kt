package com.sheltr.shared.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals

class EditDistanceTest {

    @Test
    fun identicalStringsHaveDistanceZero() = assertEquals(0, levenshtein("earthquake", "earthquake"))

    @Test
    fun classicKittenSitting() = assertEquals(3, levenshtein("kitten", "sitting"))

    @Test
    fun emptyStringDistanceIsLength() = assertEquals(3, levenshtein("", "abc"))

    @Test
    fun singleSubstitution() = assertEquals(1, levenshtein("eartquake", "earthquake"))
}
