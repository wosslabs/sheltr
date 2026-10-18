package com.sheltr.android.ui.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sheltr.android.R
import com.sheltr.shared.retrieval.RetrievalResult
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `@Config(application = Application::class)`: same reasoning as [com.sheltr.android.SheltrNavHostTest] --
 * avoids instantiating the real `SheltrApplication` for a test that exercises [SearchScreen] in isolation.
 *
 * Every [SearchViewModel] built in this file passes `ioDispatcher = Dispatchers.Unconfined`: Robolectric's
 * simulated main thread backs `viewModelScope`'s `Dispatchers.Main.immediate` hop fine, but a *second* real
 * dispatch onto the production default (`Dispatchers.IO`) would need an explicit Robolectric looper-idle step
 * this test doesn't otherwise do. `Unconfined` collapses [SearchViewModel.onSubmit]'s coroutine to run
 * synchronously within `performClick()`'s own call stack, which is exactly what these tests need: a
 * deterministic assertion immediately after the click, with no dispatcher racing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel(result: RetrievalResult = RetrievalResult.Refusal): SearchViewModel =
        SearchViewModel(
            retriever = FakeRetriever(result),
            country = null,
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun typingAndSubmittingTriggersNavigation() {
        var navigated: RetrievalResult? = null
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { navigated = it }, viewModel = viewModel(RetrievalResult.Refusal))
        }

        compose.onNodeWithTag("search-query-field").performTextInput("flood shelter")
        compose.onNodeWithTag("search-submit-button").performClick()

        assertEquals(RetrievalResult.Refusal, navigated)
    }

    @Test
    fun blankQuerySubmitDoesNotNavigate() {
        var navigated: RetrievalResult? = null
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { navigated = it }, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-submit-button").performClick()

        assertNull(navigated)
    }

    @Test
    fun hazardChipIsOptionalAndClearable() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = {}, viewModel = viewModel(), initialHazard = "FLOOD")
        }

        val floodLabel = compose.activity.getString(R.string.hazard_flood)
        compose.onNodeWithText(floodLabel).assertExists()

        compose.onNodeWithTag("search-hazard-chip").performClick()

        compose.onNodeWithText(floodLabel).assertDoesNotExist()
    }

    @Test
    fun noHazardChipWhenNotArrivedFromAHazardTap() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = {}, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-hazard-chip").assertDoesNotExist()
    }

    @Test
    fun queryFieldStartsPreFocused() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = {}, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-query-field").assertIsFocused()
    }

    @Test
    fun initialQueryPrefillsTheTextField() {
        val vm = viewModel()
        compose.setContent {
            SearchScreen(onNavigateToAnswer = {}, viewModel = vm, initialQuery = "flashlight batteries")
        }

        assertEquals("flashlight batteries", vm.query.value)
    }

    @Test
    fun rootIsTaggedSearchScreen() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = {}, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-screen").assertExists()
    }
}
