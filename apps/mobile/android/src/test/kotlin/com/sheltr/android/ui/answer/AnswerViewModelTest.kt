package com.sheltr.android.ui.answer

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.sheltr.android.ui.RetrievalResultHolder
import com.sheltr.android.ui.search.RetrieverPort
import com.sheltr.shared.retrieval.Passage
import com.sheltr.shared.retrieval.RetrievalQuery
import com.sheltr.shared.retrieval.RetrievalResult
import com.sheltr.shared.store.StoredChunk
import com.sheltr.shared.store.StoredDocument
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

private fun passageFixture(content: String = "Boil water for at least one minute."): Passage {
    val document = StoredDocument(
        id = "doc-1",
        title = "Household Water Treatment Guide",
        agency = "WHO",
        publishedDate = "2024-03-15",
        licence = "CC-BY",
        country = null,
        hazards = emptyList(),
        sourceUri = "https://example.org/water-guide",
        sourceType = "pdf",
        checksum = "c",
        fileChecksum = "fc",
    )
    val chunk = StoredChunk(id = "chunk-1", documentId = "doc-1", content = content, pageNumber = 3, position = 0)
    return Passage(chunk, document, score = 2.0, rankScore = 2.0)
}

/**
 * `@Config(application = Application::class)` + Robolectric: [AnswerViewModel] takes a real
 * [TextToSpeechController], which wraps a real (shadowed) `android.speech.tts.TextToSpeech` -- unlike
 * `SearchViewModelTest`, this cannot stay plain-JUnit. [RetrieverPort] still keeps retrieval itself
 * Android-free, same as `SearchViewModelTest`.
 *
 * Robolectric's own simulated main `Looper` is enough to back `viewModelScope`'s `Dispatchers.Main.immediate`
 * hop (no `MainDispatcherRule` needed, same as `SearchScreenTest`); `ioDispatcher` is still injected explicitly
 * so the take()-then-fallback tests can control the fallback retrieval's timing deterministically with
 * `StandardTestDispatcher`/`advanceUntilIdle` instead of racing a real thread pool.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnswerViewModelTest {

    private fun ttsController(): TextToSpeechController =
        TextToSpeechController(ApplicationProvider.getApplicationContext())

    @Test
    fun showsTheHeldResultWithoutCallingTheRetrieverWhenTakeSucceeds() {
        val held = RetrievalResult.Passages(listOf(passageFixture()))
        val resultId = RetrievalResultHolder.put(held)
        val retriever = RetrieverPort { error("must not be called: take() already produced a result") }

        val vm = AnswerViewModel(
            resultId = resultId,
            text = "water safety",
            hazard = null,
            retriever = retriever,
            country = "KE",
            ttsController = ttsController(),
        )

        assertSame(held, vm.result.value)
    }

    @Test
    fun fallsBackToRetrieverWithReconstructedQueryWhenTakeReturnsNull() = runTest {
        // This is the core bug this task exists to fix: a `null` from take() (unknown id, OR a valid id
        // revisited after process death -- see RetrievalResultHolder's doc comment) must never be shown as an
        // empty/error state. It must trigger exactly one retriever.retrieve() call, built from the nav-carried
        // text/hazard/country, and the UI must show THAT result, not nothing.
        val expected = RetrievalResult.Passages(listOf(passageFixture()))
        var receivedQuery: RetrievalQuery? = null
        var callCount = 0
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val retriever = RetrieverPort { query ->
            callCount++
            receivedQuery = query
            expected
        }

        val vm = AnswerViewModel(
            resultId = "unknown-or-process-death-restored-id",
            text = "flood shelter",
            hazard = "FLOOD",
            retriever = retriever,
            country = "KE",
            ttsController = ttsController(),
            ioDispatcher = ioDispatcher,
        )

        // Not yet resolved: the fallback retrieval is still in flight on ioDispatcher, proving it really
        // dispatches off the calling thread rather than running inline.
        assertNull(vm.result.value)
        assertEquals(0, callCount)

        advanceUntilIdle()

        assertEquals(1, callCount)
        assertEquals("flood shelter", receivedQuery?.text)
        assertEquals("KE", receivedQuery?.country)
        assertEquals(setOf("FLOOD"), receivedQuery?.activeHazards)
        assertSame(expected, vm.result.value)
    }

    @Test
    fun fallbackWithNoHazardSelectedPassesEmptyHazardSet() = runTest {
        var receivedQuery: RetrievalQuery? = null
        val retriever = RetrieverPort { query ->
            receivedQuery = query
            RetrievalResult.Refusal
        }

        val vm = AnswerViewModel(
            resultId = "gone",
            text = "earthquake kit",
            hazard = null,
            retriever = retriever,
            country = null,
            ttsController = ttsController(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        assertEquals(emptySet<String>(), receivedQuery?.activeHazards)
        assertNull(receivedQuery?.country)
        assertSame(RetrievalResult.Refusal, vm.result.value)
    }

    @Test
    fun readAloudAvailableReflectsTheTtsControllerItWasGiven() {
        val controller = ttsController()
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)

        val vm = AnswerViewModel(
            resultId = RetrievalResultHolder.put(RetrievalResult.Refusal),
            text = "x",
            hazard = null,
            retriever = RetrieverPort { error("not expected") },
            country = null,
            ttsController = controller,
        )

        assertTrue(vm.readAloudAvailable.value)
    }

    @Test
    fun onReadAloudTappedSpeaksTheTopPassageContent() {
        val passage = passageFixture("Boil water for at least one minute.")
        val resultId = RetrievalResultHolder.put(RetrievalResult.Passages(listOf(passage)))
        val controller = ttsController()
        val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        shadowEngine.onInitListener.onInit(TextToSpeech.SUCCESS)

        val vm = AnswerViewModel(
            resultId = resultId,
            text = "water safety",
            hazard = null,
            retriever = RetrieverPort { error("not expected") },
            country = null,
            ttsController = controller,
        )

        vm.onReadAloudTapped()

        assertEquals("Boil water for at least one minute.", shadowEngine.lastSpokenText)
    }

    @Test
    fun onReadAloudTappedIsANoOpForARefusal() {
        val resultId = RetrievalResultHolder.put(RetrievalResult.Refusal)
        val controller = ttsController()
        val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        shadowEngine.onInitListener.onInit(TextToSpeech.SUCCESS)

        val vm = AnswerViewModel(
            resultId = resultId,
            text = "x",
            hazard = null,
            retriever = RetrieverPort { error("not expected") },
            country = null,
            ttsController = controller,
        )

        vm.onReadAloudTapped()

        assertNull(shadowEngine.lastSpokenText)
    }

    @Test
    fun onClearedShutsDownTheTtsController() {
        // ViewModelStore.clear() is the standard, documented way to invoke a ViewModel's protected onCleared()
        // from a test without a subclass or reflection: it is exactly what Navigation Compose itself calls
        // when a back-stack entry (and the ViewModelStore scoped to it) is popped, so this exercises the real
        // mechanism, not a stand-in for it.
        val controller = ttsController()
        val shadowEngine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val vm = AnswerViewModel(
            resultId = RetrievalResultHolder.put(RetrievalResult.Refusal),
            text = "x",
            hazard = null,
            retriever = RetrieverPort { error("not expected") },
            country = null,
            ttsController = controller,
        )
        val store = ViewModelStore()
        store.put("answer", vm)

        assertTrue(!shadowEngine.isShutdown)
        store.clear()

        assertTrue(shadowEngine.isShutdown)
    }

    @Test(expected = IllegalStateException::class)
    fun throwsRatherThanSilentlyRefusingWhenNeitherTakeNorTextCanProduceAResult() {
        // Route.Answer's one caller always supplies text (see its doc comment); resultId/text both missing
        // would mean that contract broke. AnswerViewModel must fail loudly here, not quietly show a Refusal
        // that would look to the user like a deliberate "no answer" rather than a bug.
        AnswerViewModel(
            resultId = "unknown",
            text = null,
            hazard = null,
            retriever = RetrieverPort { error("not expected") },
            country = null,
            ttsController = ttsController(),
        )
    }
}
