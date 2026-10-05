package com.sheltr.publisher

import kotlin.test.Test
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun usageNamesTheTool() {
        assertTrue(USAGE.startsWith("usage: publisher"))
    }
}
