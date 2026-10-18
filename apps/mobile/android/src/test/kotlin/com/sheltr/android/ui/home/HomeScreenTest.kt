package com.sheltr.android.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.sheltr.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `@Config(application = Application::class)`: same reasoning as [com.sheltr.android.SheltrNavHostTest] --
 * avoids instantiating the real `SheltrApplication` (and its first-run sample-bundle install, which needs a
 * real SQLite driver unavailable under Robolectric) for a test that only exercises [HomeScreen] in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val allTiles = HomeViewModel().hazardTiles

    @Test
    fun showsAllHazardTilesWithLabelAndIcon() {
        compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = {}) }

        // The grid is a LazyVerticalGrid: tiles past the first screenful aren't composed until scrolled into
        // view, so each lookup first scrolls the grid to the node it's about to assert on.
        allTiles.forEach { tile ->
            val label = compose.activity.getString(tile.label)
            compose.onNodeWithTag("home-hazard-grid").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun everyHazardTileHasANonEmptyContentDescriptionEqualToItsLabel() {
        compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = {}) }

        allTiles.forEach { tile ->
            val label = compose.activity.getString(tile.label)
            assertFalse("label for ${tile.hazard} must not be blank", label.isBlank())
            // A node matching this exact content description must exist: if the tile were icon-only (no
            // explicit, label-equal description), this lookup would fail.
            compose.onNodeWithTag("home-hazard-grid").performScrollToNode(hasContentDescription(label))
            compose.onNodeWithContentDescription(label).assertExists()
        }
    }

    @Test
    fun tappingAHazardTileReportsItsHazardName() {
        var tapped: String? = null
        compose.setContent { HomeScreen(onHazardTapped = { tapped = it }, onSearchSubmit = {}) }

        val earthquakeTile = allTiles.first { it.hazard == "EARTHQUAKE" }
        val label = compose.activity.getString(earthquakeTile.label)
        compose.onNodeWithText(label).performClick()

        assertEquals("EARTHQUAKE", tapped)
    }

    @Test
    fun submittingSearchTextReportsTrimmedQuery() {
        var submitted: String? = null
        compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = { submitted = it }) }

        compose.onNodeWithTag("home-search-field").performTextInput("  flashlight batteries  ")
        compose.onNodeWithTag("home-search-field").performImeAction()

        assertEquals("flashlight batteries", submitted)
    }

    @Test
    fun blankSearchSubmitDoesNotReportAnything() {
        var submitted: String? = null
        compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = { submitted = it }) }

        compose.onNodeWithTag("home-search-field").performImeAction()

        assertNull(submitted)
    }

    @Test
    fun rootIsTaggedHomeScreen() {
        compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = {}) }

        compose.onNodeWithTag("home-screen").assertExists()
    }

    @Test
    fun settingsButtonIsLabeledNotIconOnlyAndInvokesCallback() {
        // Regression coverage for review Finding 1: Settings was unreachable from any screen. This asserts both
        // that the button exists with a visible label (not icon-only, the Global Constraint on tappable
        // controls) and that tapping it actually invokes the navigation callback SheltrNavHost wires up.
        var tapped = false
        compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = {}, onSettingsTapped = { tapped = true }) }

        val label = compose.activity.getString(R.string.home_settings_button_label)
        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithTag("home-settings-button").performClick()

        assertTrue(tapped)
    }
}
