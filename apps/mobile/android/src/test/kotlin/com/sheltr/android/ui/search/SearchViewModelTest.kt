package com.sheltr.android.ui.search

import com.sheltr.shared.retrieval.RetrievalQuery
import com.sheltr.shared.retrieval.RetrievalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `@get:Rule val mainDispatcherRule`: [androidx.lifecycle.viewModelScope] resolves `Dispatchers.Main` at launch
 * time; under plain JUnit (no Robolectric/Android runtime) there is no Main dispatcher unless one is installed,
 * so every test here must install one before constructing a [SearchViewModel] and remove it afterwards.
 * [UnconfinedTestDispatcher] runs [SearchViewModel.onSubmit]'s `viewModelScope.launch { ... }` body immediately
 * up to its first real suspension point, which is the test's actual object of interest: the `withContext`
 * hop onto [SearchViewModel]'s separately-injected `ioDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) :
    TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** Records the last [RetrievalQuery] it was asked to retrieve and always answers with [result]. */
internal class FakeRetriever(private val result: RetrievalResult) : RetrieverPort {
    var lastQuery: RetrievalQuery? = null
        private set

    override fun retrieve(query: RetrievalQuery): RetrievalResult {
        lastQuery = query
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    internal val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun submitCallsRetrieverWithCurrentQueryAndHazardAndCountry() = runTest {
        // A StandardTestDispatcher (not eager/Unconfined, unlike Dispatchers.Main above) proves onSubmit truly
        // dispatches the retrieval elsewhere rather than running it synchronously: the assertNull below only
        // holds if execution genuinely suspended at the ioDispatcher hop instead of running inline.
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val retriever = FakeRetriever(result = RetrievalResult.Refusal)
        val vm = SearchViewModel(retriever, country = "KE", ioDispatcher = ioDispatcher)
        vm.onQueryChange("flood shelter")
        vm.onHazardSelected("FLOOD")

        var navigated: RetrievalResult? = null
        vm.onSubmit { navigated = it }

        assertNull("retrieve() must not run inline on the calling dispatcher", retriever.lastQuery)

        advanceUntilIdle()

        assertEquals("flood shelter", retriever.lastQuery?.text)
        assertEquals(setOf("FLOOD"), retriever.lastQuery?.activeHazards)
        assertEquals("KE", retriever.lastQuery?.country)
        assertEquals(RetrievalResult.Refusal, navigated)
    }

    @Test
    fun submitWithNoHazardSelectedPassesEmptyHazardSet() = runTest {
        val retriever = FakeRetriever(result = RetrievalResult.Refusal)
        val vm = SearchViewModel(
            retriever,
            country = null,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onQueryChange("flashlight batteries")

        vm.onSubmit { }
        advanceUntilIdle()

        assertEquals(emptySet<String>(), retriever.lastQuery?.activeHazards)
        assertNull(retriever.lastQuery?.country)
    }

    @Test
    fun blankQueryDoesNotCallRetrieverOrNavigate() = runTest {
        val retriever = FakeRetriever(result = RetrievalResult.Refusal)
        val vm = SearchViewModel(
            retriever,
            country = "KE",
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onQueryChange("   ")

        var navigated: RetrievalResult? = null
        vm.onSubmit { navigated = it }
        advanceUntilIdle()

        assertNull(retriever.lastQuery)
        assertNull(navigated)
    }

    @Test
    fun onHazardSelectedNullClearsActiveHazard() {
        val retriever = FakeRetriever(result = RetrievalResult.Refusal)
        val vm = SearchViewModel(retriever, country = null)

        vm.onHazardSelected("FLOOD")
        assertEquals("FLOOD", vm.activeHazard.value)

        vm.onHazardSelected(null)
        assertNull(vm.activeHazard.value)
    }

    @Test
    fun onQueryChangeUpdatesQueryState() {
        val retriever = FakeRetriever(result = RetrievalResult.Refusal)
        val vm = SearchViewModel(retriever, country = null)

        vm.onQueryChange("earthquake kit")

        assertEquals("earthquake kit", vm.query.value)
    }
}
