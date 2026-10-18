package com.sheltr.shared.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [AndroidSettingsStore] against a real (Robolectric-shadowed) [android.content.Context] and a real
 * AndroidX DataStore -- not an in-memory fake -- so these assertions cover actual preference-file persistence,
 * not just object identity. Each test method gets a fresh Robolectric application context, so
 * [putThenGetRoundTripsEveryField] constructing a second [AndroidSettingsStore] instance (rather than reusing
 * the one it already has) is what proves the value survived a real write/read round trip through DataStore
 * rather than an in-memory field on the first instance.
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(
            syncFrequency = SyncFrequency.DAILY,
            allowMobileData = true,
            country = "KE",
            textScale = TextScale.LARGE,
            highContrast = true,
        )
        AndroidSettingsStore(context).put(settings)

        val reloaded = AndroidSettingsStore(context).get()

        assertEquals(settings, reloaded)
    }
}
