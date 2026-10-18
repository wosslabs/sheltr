package com.sheltr.android.ui.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sheltr.android.R
import com.sheltr.shared.retrieval.RetrievalResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel(
        result: RetrievalResult = RetrievalResult.Refusal,
        initialHazard: String? = null,
        initialQuery: String? = null,
        retriever: FakeRetriever = FakeRetriever(result),
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): SearchViewModel =
        SearchViewModel(
            retriever = retriever,
            country = null,
            initialHazard = initialHazard,
            initialQuery = initialQuery,
            ioDispatcher = ioDispatcher,
        )

    @Test
    fun typingAndSubmittingTriggersNavigation() {
        var navigated: RetrievalResult? = null
        var navigatedText: String? = null
        compose.setContent {
            SearchScreen(
                onNavigateToAnswer = { result, submittedText, _ ->
                    navigated = result
                    navigatedText = submittedText
                },
                viewModel = viewModel(RetrievalResult.Refusal),
            )
        }

        compose.onNodeWithTag("search-query-field").performTextInput("flood shelter")
        compose.onNodeWithTag("search-submit-button").performClick()

        assertEquals(RetrievalResult.Refusal, navigated)
        // SearchScreen must forward the exact submitted text through to Route.Answer's eventual nav
        // argument, not just the RetrievalResult -- see SearchViewModel.onSubmit's doc comment for why.
        assertEquals("flood shelter", navigatedText)
    }

    @Test
    fun blankQuerySubmitDoesNotNavigate() {
        var navigated: RetrievalResult? = null
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { result, _, _ -> navigated = result }, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-submit-button").performClick()

        assertNull(navigated)
    }

    @Test
    fun hazardChipIsOptionalAndClearable() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { _, _, _ -> }, viewModel = viewModel(initialHazard = "FLOOD"))
        }

        val floodLabel = compose.activity.getString(R.string.hazard_flood)
        compose.onNodeWithText(floodLabel).assertExists()

        compose.onNodeWithTag("search-hazard-chip").performClick()

        compose.onNodeWithText(floodLabel).assertDoesNotExist()
    }

    @Test
    fun noHazardChipWhenNotArrivedFromAHazardTap() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { _, _, _ -> }, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-hazard-chip").assertDoesNotExist()
    }

    @Test
    fun queryFieldStartsPreFocused() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { _, _, _ -> }, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-query-field").assertIsFocused()
    }

    @Test
    fun initialQueryPrefillsTheTextField() {
        val vm = viewModel(initialQuery = "flashlight batteries")
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { _, _, _ -> }, viewModel = vm)
        }

        assertEquals("flashlight batteries", vm.query.value)
    }

    @Test
    fun rootIsTaggedSearchScreen() {
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { _, _, _ -> }, viewModel = viewModel())
        }

        compose.onNodeWithTag("search-screen").assertExists()
    }

    @Test
    fun submitButtonIsDisabledWhileARetrievalIsInFlight() {
        // Regression coverage for Finding 6: a retriever that never completes (nothing advances its
        // StandardTestDispatcher's scheduler) keeps SearchViewModel.isSubmitting true, which must disable the
        // button so a user cannot push a second Answer entry onto the back stack with a second tap.
        val vm = viewModel(ioDispatcher = StandardTestDispatcher())
        compose.setContent {
            SearchScreen(onNavigateToAnswer = { _, _, _ -> }, viewModel = vm)
        }
        compose.onNodeWithTag("search-query-field").performTextInput("flood shelter")
        compose.onNodeWithTag("search-submit-button").assertIsEnabled()

        compose.onNodeWithTag("search-submit-button").performClick()

        compose.onNodeWithTag("search-submit-button").assertIsNotEnabled()
    }
}
