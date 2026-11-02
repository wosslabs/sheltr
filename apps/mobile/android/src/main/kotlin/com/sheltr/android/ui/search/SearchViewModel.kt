package com.sheltr.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Thin seam so [SearchViewModel] is unit-testable without a real `StoreReader`-backed
 * [com.sheltr.shared.retrieval.Retriever], which needs a live SQLite connection to construct. The shared
 * module's `Retriever` has this exact method signature already; [com.sheltr.android.ui.SheltrNavHost] adapts
 * the real one to this interface with a one-line SAM conversion (`RetrieverPort(retriever::retrieve)`) at the
 * composition root, so the shared module itself needs no change and gains no dependency on this app module
 * (the dependency arrow still points app -> shared, never the other way). A small, justified seam at the UI
 * boundary per CLAUDE.md's caution against "excessive interfaces with only one trivial implementation" -- this
 * one earns its place because the alternative is pulling a real SQLite-backed `Retriever` into every
 * `SearchViewModel` unit test.
 */
fun interface RetrieverPort {
    fun retrieve(query: RetrievalQuery): RetrievalResult
}

/**
 * Search screen state and its one user intent: submit the current query (plus any selected hazard) against
 * [retriever].
 *
 * [country] is [com.sheltr.shared.settings.AppSettings.country] read once, at construction time, by
 * [com.sheltr.android.ui.SheltrNavHost] -- not observed reactively. Settings rarely change mid-search, and a
 * fresh [SearchViewModel] is built the next time this screen is navigated to anyway (same "no reactive settings
 * Flow needed" simplicity level already established for `MainActivity`).
 *
 * [initialHazard]/[initialQuery] come from [com.sheltr.android.ui.SheltrNavHost]'s Search route's nav arguments
 * (`Route.Search.destination(hazard, query)`) and seed [_activeHazard]/[_query] exactly once, here at
 * construction -- not from a `LaunchedEffect` in `SearchScreen` keyed on first composition. That used to be
 * where this was applied, but a `NavBackStackEntry`'s `ViewModelStore` (and therefore this ViewModel instance)
 * survives the Search composable leaving and re-entering composition -- which happens on every back-navigation
 * from Answer, and on every configuration change -- while a `LaunchedEffect(Unit)` in the Composable re-runs on
 * every one of those re-entries. That silently overwrote a hazard chip the user had dismissed, or a query they
 * had edited, back to the original nav arguments every time. Seeding the state here instead, in a constructor
 * that only ever runs once for this ViewModel's lifetime, fixes that -- the same one-time-at-construction
 * convention [com.sheltr.android.ui.answer.AnswerViewModel]'s `init` already established for `resultId`/`text`/
 * `hazard`.
 *
 * [ioDispatcher] defaults to [Dispatchers.IO] in production -- the shared module's retrieval is synchronous
 * SQLite I/O and must not run on the main thread. The constructor parameter exists purely so tests can supply a
 * [kotlinx.coroutines.test.TestDispatcher] (sharing the test's own scheduler) or [kotlinx.coroutines.Dispatchers.Unconfined]
 * instead, making [onSubmit] deterministic under `runTest`/`advanceUntilIdle` rather than racing a real thread
 * pool.
 */
class SearchViewModel(
    private val retriever: RetrieverPort,
    private val country: String?,
    initialHazard: String? = null,
    initialQuery: String? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _query = MutableStateFlow(initialQuery ?: "")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _activeHazard = MutableStateFlow(initialHazard)
    val activeHazard: StateFlow<String?> = _activeHazard.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)

    /** True from [onSubmit] starting a retrieval until it completes. Gates `SearchScreen`'s submit button. */
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    fun onQueryChange(text: String) {
        _query.value = text
    }

    /** [hazard] null clears the optional hazard chip (the user dismissed it, or never arrived with one). */
    fun onHazardSelected(hazard: String?) {
        _activeHazard.value = hazard
    }

    /**
     * Trims [query]; blank text is "nothing to search" and does nothing (same convention as
     * [com.sheltr.android.ui.home.HomeViewModel.onSearchSubmit]) -- an empty-text query being sent to the
     * shared module's FTS5-backed search has no well-defined relevance score to gate on.
     *
     * Otherwise runs [retriever] on [ioDispatcher] inside [viewModelScope] and, once it completes, invokes
     * [onNavigateToAnswer] back on the main thread with the [RetrievalResult] *and* the exact `submittedText`/
     * `submittedHazard` that produced it. This ViewModel only knows it ran a query; the caller (`SearchScreen`,
     * wired up by `SheltrNavHost`) decides what "navigate to Answer" means -- but it needs those two values,
     * not just the result, because `Route.Answer` carries them as nav arguments so Answer can re-run the same
     * retrieval if its process is recreated before it ever reads the in-memory
     * [com.sheltr.android.ui.RetrievalResultHolder] (see that class's doc comment for why this is necessary,
     * not optional).
     *
     * `submittedText`/`submittedHazard` are captured into local `val`s *before* the dispatcher hop, not read
     * back from [query]/[activeHazard] after it: those [StateFlow]s could have changed (the user kept typing)
     * while retrieval was in flight, and the values that must reach `Route.Answer` are the ones that actually
     * produced this [RetrievalResult], not whatever is in the text field by the time the callback fires.
     *
     * Guarded by [isSubmitting]: a second tap (or a double `onSearch` IME event) while a retrieval is already in
     * flight is a no-op instead of pushing a second Answer entry onto the back stack. `SearchScreen` also
     * disables its submit button while [isSubmitting] is true, but that is a UI convenience, not the actual
     * guard -- this check is what makes double-submission impossible regardless of which caller triggers it.
     */
    fun onSubmit(onNavigateToAnswer: (result: RetrievalResult, submittedText: String, submittedHazard: String?) -> Unit) {
        if (_isSubmitting.value) return
        val text = query.value.trim()
        if (text.isEmpty()) return
        val hazard = activeHazard.value
        val request = RetrievalQuery(
            text = text,
            country = country,
            activeHazards = hazard?.let { setOf(it) } ?: emptySet(),
        )
        _isSubmitting.value = true
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { retriever.retrieve(request) }
            _isSubmitting.value = false
            onNavigateToAnswer(result, text, hazard)
        }
    }
}
