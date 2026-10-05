package com.sheltr.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppSettingsTest {

    @Test
    fun defaultsAreWeeklyMobileDataOffNoCountryNormalText() {
        val defaults = AppSettings()
        assertEquals(SyncFrequency.WEEKLY, defaults.syncFrequency)
        assertFalse(defaults.allowMobileData)
        assertNull(defaults.country)
        assertEquals(TextScale.NORMAL, defaults.textScale)
    }

    @Test
    fun acceptsTwoUppercaseLettersAndNull() {
        assertEquals("GB", AppSettings(country = "GB").country)
        assertNull(AppSettings(country = null).country)
    }

    @Test
    fun rejectsOtherCountryValues() {
        for (bad in listOf("gb", "GBR", "1A", "", "G", "G-", "Gb", "ÉS", "GB\u0000")) {
            assertFailsWith<IllegalArgumentException>("country '$bad' should be rejected") {
                AppSettings(country = bad)
            }
        }
    }

    @Test
    fun intervalsMatchTheSpec() {
        assertNull(SyncFrequency.MANUAL.intervalSeconds)
        assertEquals(86_400L, SyncFrequency.DAILY.intervalSeconds)
        assertEquals(604_800L, SyncFrequency.WEEKLY.intervalSeconds)
        assertEquals(30L * 86_400L, SyncFrequency.MONTHLY.intervalSeconds)
    }

    @Test
    fun inMemoryStoreReturnsWhatWasPut() {
        val store = InMemorySettingsStore()
        assertEquals(AppSettings(), store.get())
        val changed = AppSettings(syncFrequency = SyncFrequency.MONTHLY, allowMobileData = true, country = "NZ", textScale = TextScale.EXTRA_LARGE)
        store.put(changed)
        assertEquals(changed, store.get())
    }
}
