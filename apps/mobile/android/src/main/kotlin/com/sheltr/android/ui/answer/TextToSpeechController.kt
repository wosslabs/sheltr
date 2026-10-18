package com.sheltr.android.ui.answer

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around Android's [TextToSpeech] for reading an Answer passage aloud.
 *
 * The corpus (the sample bundle and every document the server ingests) is English-only regardless of the
 * device's or app's own UI locale, so [speak] must always use [Locale.ENGLISH] -- never the ambient
 * default/device locale -- or a non-English-locale user would hear the English source text mispronounced in a
 * voice selected for a language the content isn't actually in.
 *
 * [isAvailable] starts `false` and flips to `true` only once *both* of these hold:
 * - the engine's own async init succeeds ([TextToSpeech.OnInitListener.onInit] reports [TextToSpeech.SUCCESS]);
 * - [Locale.ENGLISH] is actually installed ([TextToSpeech.setLanguage] does not report
 *   [TextToSpeech.LANG_MISSING_DATA] or [TextToSpeech.LANG_NOT_SUPPORTED]).
 *
 * Both failure modes are real on real devices -- no TTS engine installed at all, or one installed without an
 * English voice pack -- and must degrade to "read-aloud unavailable," never a crash: [speak] is a safe no-op
 * whenever [isAvailable] is false, so a caller that taps read-aloud anyway (e.g. a UI bug, or a race right as
 * the engine fails) never crashes the screen.
 */
class TextToSpeechController(context: Context) {

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val engine: TextToSpeech = TextToSpeech(context) { status -> onEngineInitialized(status) }

    private fun onEngineInitialized(status: Int) {
        _isAvailable.value = status == TextToSpeech.SUCCESS && isEnglishInstalled()
    }

    private fun isEnglishInstalled(): Boolean {
        val result = engine.setLanguage(Locale.ENGLISH)
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** No-op when [isAvailable] is false. Callers should check it first (for UI state), but this stays safe either way. */
    fun speak(text: String) {
        if (!_isAvailable.value) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        engine.stop()
    }

    /** Releases the engine's resources. Must be called once this controller is no longer needed. */
    fun shutdown() {
        engine.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "sheltr-answer-read-aloud"
    }
}
