package com.sheltr.shared.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [AndroidSettingsStore] against a real (Robolectric-shadowed) [android.content.Context] and a real
 * AndroidX DataStore -- not an in-memory fake -- so [putThenGetRoundTripsEveryField]'s `put` writes to an actual
 * preferences file on disk before `get` reads it back, rather than merely asserting an in-memory field was set.
 * Each test constructs its own [AndroidSettingsStore] instance: AndroidX DataStore allows only one live instance
 * per backing file in a process, so a second instance constructed against the same [android.content.Context]
 * would throw instead of exercising anything useful -- Robolectric gives each test its own isolated context and
 * filesystem, so the two tests here do not conflict with each other.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSettingsStoreTest {

    @Test
    fun getReturnsDefaultsBeforeAnyPut() {
        val store = AndroidSettingsStore(ApplicationProvider.getApplicationContext())
        assertEquals(AppSettings(), store.get())
    }

    @Test
    fun putThenGetRoundTripsEveryField() {
        val store = AndroidSettingsStore(ApplicationProvider.getApplicationContext())
        val settings = AppSettings(
            syncFrequency = SyncFrequency.DAILY,
            allowMobileData = true,
            country = "KE",
            textScale = TextScale.LARGE,
            highContrast = true,
        )
        store.put(settings)

        assertEquals(settings, store.get())
    }
}
