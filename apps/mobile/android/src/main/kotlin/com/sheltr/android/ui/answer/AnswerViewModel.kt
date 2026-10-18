package com.sheltr.android.ui.answer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheltr.android.ui.RetrievalResultHolder
import com.sheltr.android.ui.search.RetrieverPort
import com.sheltr.shared.retrieval.RetrievalQuery
import com.sheltr.shared.retrieval.RetrievalResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Answer screen state: the [RetrievalResult] to show, and read-aloud control over its top passage.
 *
 * ## The take()-then-fallback contract
 *
 * [resultId] is the key Search put its [RetrievalResult] under in [RetrievalResultHolder]. [RetrievalResultHolder.take]
 * is called exactly once, in [init]. A hit (non-null) is the common case: the result Search just computed,
 * shown as-is, with no retrieval re-run. A `null` is **not** "no answer was found" (see that holder's doc
 * comment for the full reasoning) -- it only ever means this process never held that id, which is true both for
 * a stale/unknown id and for a perfectly valid one revisited after Android reclaimed this process in the
 * background and recreated it from the (persisted, via `SavedStateRegistry`) nav back stack. In that second
 * case, [text]/[hazard] -- the exact inputs that produced [resultId]'s original result, carried as nav arguments
 * precisely for this -- are used to re-run the same deterministic local retrieval and show *that*, rather than
 * show an empty or error state for a search that actually succeeded.
 *
 * [retriever] is [RetrieverPort], not the shared module's concrete `Retriever` class, for the same reason
 * [SearchViewModel][com.sheltr.android.ui.search.SearchViewModel] takes it: a real `Retriever` needs a real
 * `StoreReader` (SQLite-backed in production), and this is the same thin, already-justified seam at the UI
 * boundary so this ViewModel's own unit tests don't need one either (see `RetrieverPort`'s doc comment).
 * [com.sheltr.android.ui.SheltrNavHost] adapts the real `Retriever` it already holds to this interface with the
 * identical one-line SAM conversion it already uses for Search (`RetrieverPort(retriever::retrieve)`).
 *
 * [country] is [com.sheltr.shared.settings.AppSettings.country] read once by `SheltrNavHost` at construction
 * time -- same convention as `SearchViewModel.country`. This is still "fresh" relative to the fallback: it
 * reflects the user's settings at the moment Answer (re)composes, which is the only thing that matters here --
 * see [RetrievalResultHolder]'s doc comment for why `country`, unlike `text`/`hazard`, is deliberately NOT
 * carried as a nav argument (it does not need to survive process death; it is read fresh from settings either
 * way, whether that happens a moment before this constructor runs or inside it makes no observable difference).
 *
 * [ttsController]'s [TextToSpeechController.isAvailable] is exposed as [readAloudAvailable]; [onReadAloudTapped]
 * speaks the top passage's content through it. [ioDispatcher] defaults to [Dispatchers.IO] for the same reason
 * `SearchViewModel.ioDispatcher` does: the fallback retrieval is synchronous SQLite I/O via [retriever] and must
 * not run on the main thread; tests substitute a [kotlinx.coroutines.test.TestDispatcher].
 */
class AnswerViewModel(
    resultId: String?,
    text: String?,
    hazard: String?,
    private val retriever: RetrieverPort,
    private val country: String?,
    private val ttsController: TextToSpeechController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _result = MutableStateFlow<RetrievalResult?>(null)

    /** Null only transiently, while the take()-then-fallback retrieval above is still in flight. */
    val result: StateFlow<RetrievalResult?> = _result.asStateFlow()

    val readAloudAvailable: StateFlow<Boolean> = ttsController.isAvailable

    init {
        val held = resultId?.let { RetrievalResultHolder.take(it) }
        when {
            held != null -> _result.value = held
            // text is always supplied by the one caller that builds Route.Answer (see that route's doc
            // comment), so this branch is the real fallback path. The `else` below is an unreachable-in-practice
            // safety net, not a path any real navigation produces.
            text != null -> rerunRetrieval(text, hazard)
            else -> _result.value = RetrievalResult.Refusal
        }
    }

    private fun rerunRetrieval(text: String, hazard: String?) {
        val query = RetrievalQuery(
            text = text,
            country = country,
            activeHazards = hazard?.let { setOf(it) } ?: emptySet(),
        )
        viewModelScope.launch {
            _result.value = withContext(ioDispatcher) { retriever.retrieve(query) }
        }
    }

    /** Speaks the top (best-ranked) passage's content aloud. A no-op when there is no [RetrievalResult.Passages]. */
    fun onReadAloudTapped() {
        val topPassage = (_result.value as? RetrievalResult.Passages)?.passages?.firstOrNull() ?: return
        ttsController.speak(topPassage.chunk.content)
    }

    /** Releases the TTS engine once this screen is no longer on the back stack. */
    override fun onCleared() {
        ttsController.shutdown()
    }
}
