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
 * Flow needed" simplicity level this plan's Task 1 already established for `MainActivity`).
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _activeHazard = MutableStateFlow<String?>(null)
    val activeHazard: StateFlow<String?> = _activeHazard.asStateFlow()

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
     * [onNavigateToAnswer] back on the main thread with the [RetrievalResult]. This ViewModel only knows it ran
     * a query; the caller (`SearchScreen`, wired up by `SheltrNavHost`) decides what "navigate to Answer" means.
     */
    fun onSubmit(onNavigateToAnswer: (RetrievalResult) -> Unit) {
        val text = query.value.trim()
        if (text.isEmpty()) return
        val request = RetrievalQuery(
            text = text,
            country = country,
            activeHazards = activeHazard.value?.let { setOf(it) } ?: emptySet(),
        )
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { retriever.retrieve(request) }
            onNavigateToAnswer(result)
        }
    }
}
