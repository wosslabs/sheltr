package com.sheltr.android.ui.answer

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * `@Config(application = Application::class)`: same reasoning as [com.sheltr.android.SheltrNavHostTest] -- a
 * bare [Application] context is all [TextToSpeechController] needs, and avoids instantiating the real
 * `SheltrApplication`.
 *
 * `ShadowTextToSpeech.getLastTextToSpeechInstance()` + `shadowOf(...)`: Robolectric's shadow does not invoke
 * [TextToSpeech.OnInitListener.onInit] automatically (that is left to tests to drive, since real engine
 * availability is exactly what varies device to device); these tests call it directly through the shadow,
 * exactly as production code would receive it from a real engine. `ShadowTextToSpeech`'s `@Resetter`-annotated
 * `reset()` runs automatically between tests (Robolectric's standard mechanism), so static shadow state (the
 * last instance, the installed-language set) never leaks across tests in this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TextToSpeechControllerTest {

    @Test
    fun isAvailableFalseWhenEngineInitFails() {
        val controller = TextToSpeechController(ApplicationProvider.getApplicationContext())
        val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

        shadowEngine.onInitListener.onInit(TextToSpeech.ERROR)

        assertFalse(controller.isAvailable.value)
        // Must be a safe no-op, not a crash, when read-aloud is invoked anyway.
        controller.speak("evacuate immediately")
        assertNull(shadowEngine.lastSpokenText)
    }

    @Test
    fun isAvailableTrueWhenEngineInitSucceedsAndEnglishIsInstalled() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        val controller = TextToSpeechController(ApplicationProvider.getApplicationContext())
        val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

        shadowEngine.onInitListener.onInit(TextToSpeech.SUCCESS)

        assertTrue(controller.isAvailable.value)
        assertEquals(Locale.ENGLISH, shadowEngine.currentLanguage)
    }

    @Test
    fun isAvailableFalseWhenEnglishVoiceDataIsMissingEvenIfEngineInitSucceeds() {
        // Deliberately no ShadowTextToSpeech.addLanguageAvailability call: isLanguageAvailable() then defaults
        // to LANG_NOT_SUPPORTED, simulating an engine installed without an English voice pack.
        val controller = TextToSpeechController(ApplicationProvider.getApplicationContext())
        val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

        shadowEngine.onInitListener.onInit(TextToSpeech.SUCCESS)

        assertFalse(controller.isAvailable.value)
    }

    @Test
    fun speakForcesEnglishRegardlessOfDeviceDefaultLocale() {
        val originalDefault = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRENCH)
            ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
            val controller = TextToSpeechController(ApplicationProvider.getApplicationContext())
            val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
            shadowEngine.onInitListener.onInit(TextToSpeech.SUCCESS)

            assertEquals(Locale.ENGLISH, shadowEngine.currentLanguage)

            controller.speak("evacuate immediately")
            assertEquals("evacuate immediately", shadowEngine.lastSpokenText)
        } finally {
            Locale.setDefault(originalDefault)
        }
    }
}
