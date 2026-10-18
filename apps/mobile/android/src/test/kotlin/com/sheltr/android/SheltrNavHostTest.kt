package com.sheltr.android

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.sheltr.android.ui.SheltrNavHost
import com.sheltr.shared.retrieval.Retriever
import com.sheltr.shared.settings.InMemorySettingsStore
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.SearchHit
import com.sheltr.shared.store.StoreDriverFactory
import com.sheltr.shared.store.StoreReader
import com.sheltr.shared.store.StoredChunk
import com.sheltr.shared.store.StoredDocument
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A [ContentStore] over a fresh, empty directory: no `content.db` exists, so [ContentStore.openActive] never
 * calls [drivers] (see that method's own doc comment) -- lets every test below construct a real [ContentStore]
 * (which [SheltrNavHost]'s Settings route needs) without a working SQLite driver, unavailable under Robolectric
 * (see [com.sheltr.shared.store.AndroidStoreDriverFactory]'s doc comment).
 */
private fun freshContentStore(): ContentStore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storeDirectory = File(context.cacheDir, "nav-host-test-${System.nanoTime()}").apply { mkdirs() }
    return ContentStore(
        storeDirectory.absolutePath,
        StoreDriverFactory { _, _ -> error("not expected to be called: no content.db exists in this fresh directory") },
    )
}

/**
 * A [StoreReader] with no content: [search] always returns no hits, so a real [Retriever] built over this
 * always answers [com.sheltr.shared.retrieval.RetrievalResult.Refusal] -- enough for the end-to-end nav test
 * below, which only needs Answer to render *something*, not a specific passage.
 */
private class EmptyStoreReader : StoreReader {
    override fun bundleVersion(): String? = null
    override fun document(id: String): StoredDocument? = null
    override fun chunks(documentId: String): List<StoredChunk> = emptyList()
    override fun search(query: String, limit: Int): List<SearchHit> = emptyList()
}

/**
 * Smoke test for [SheltrNavHost]: proves the NavHost actually renders and starts on the Home route, not just
 * that the composable compiles. Tasks 2-5 replace each stub screen; this test should keep passing unchanged as
 * long as the Home route's test tag survives.
 *
 * `@Config(application = Application::class)` overrides the manifest's `SheltrApplication` with a bare
 * [Application] for this test: without it, Robolectric would instantiate the real `SheltrApplication` to back
 * the test's [ComponentActivity], whose `onCreate` would start the real first-run sample-bundle install -- which
 * opens a real [com.sheltr.shared.store.AndroidStoreDriverFactory] driver and so requires requery's native SQLite
 * library, unavailable under Robolectric (see that factory's own doc comment). This test exercises [SheltrNavHost]
 * in isolation with fakes below, not that install path, so the real `SheltrApplication` must not run here at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SheltrNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startsOnHomeRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeDirectory = File(context.cacheDir, "nav-host-test-${System.nanoTime()}").apply { mkdirs() }
        // No content.db exists in this fresh directory, so ContentStore never calls the driver factory (see
        // ContentStore.openActive): the stub screens under test don't read the store, so a driver that refuses
        // to open anything is fine here, and lets this test avoid AndroidStoreDriverFactory's requery native
        // library, which cannot load under Robolectric.
        val contentStore = ContentStore(
            storeDirectory.absolutePath,
            StoreDriverFactory { _, _ -> error("not expected to be called: the stub screens under test don't open the store") },
        )

        compose.setContent {
            SheltrNavHost(
                contentStore = contentStore,
                settingsStore = InMemorySettingsStore(),
                retriever = Retriever(contentStore),
                syncEngine = { error("not expected to be called in this test") },
                publisherBaseUrl = null,
                publisherPublicKey = null,
                onSettingsChanged = {},
            )
        }

        compose.onNodeWithTag("home-screen").assertExists()
    }

    /**
     * Regression coverage for review Finding 1: before this fix, nothing anywhere called
     * `navController.navigate(Route.Settings.path)` -- Settings (country, language, text size, high contrast,
     * sync) was completely unreachable in the shipped app despite being fully implemented. This drives the real
     * [SheltrNavHost] (not just [com.sheltr.android.ui.home.HomeScreen] in isolation, as `HomeScreenTest`'s own
     * regression test does) from Home through the new settings button to the real Settings route.
     */
    @Test
    fun settingsScreenIsReachableFromHome() {
        val contentStore = freshContentStore()
        compose.setContent {
            SheltrNavHost(
                contentStore = contentStore,
                settingsStore = InMemorySettingsStore(),
                retriever = Retriever(EmptyStoreReader()),
                syncEngine = { error("not expected to be called in this test") },
                publisherBaseUrl = null,
                publisherPublicKey = null,
                onSettingsChanged = {},
            )
        }

        compose.onNodeWithTag("home-settings-button").performClick()

        compose.onNodeWithTag("settings-screen").assertExists()
    }

    /**
     * The full happy-path flow this plan's six tasks were built for, driven end to end through the real
     * [SheltrNavHost] with a fake [StoreReader]/in-memory [InMemorySettingsStore] -- not through any one
     * screen's own isolated test. Proves two things at once:
     *
     * - **Finding 1** (indirectly): if Settings were the only unreachable screen, this flow alone wouldn't
     *   catch it -- but it establishes the baseline "the rest of the app actually works end to end" that
     *   [settingsScreenIsReachableFromHome] above adds Settings reachability to, the combination the review
     *   asked for.
     * - **Finding 2** directly: dismisses the hazard chip Home's tap carried into Search, submits, lets Answer
     *   render, then pops back to Search via the *same* `NavController` (so the *same* `SearchViewModel`
     *   instance, scoped to that still-alive back stack entry, is still backing the screen) and asserts the
     *   dismissed hazard chip has not reappeared. Before the fix, `SearchScreen`'s `LaunchedEffect(Unit)`
     *   re-applied `initialHazard` every time the composable re-entered composition -- which back navigation
     *   from Answer does -- silently undoing the user's dismissal. After the fix (seeding
     *   [com.sheltr.android.ui.search.SearchViewModel]'s state once, in its constructor, not in a
     *   recomposition-triggered effect), it cannot reappear: nothing left in the Composable re-applies it.
     */
    @Test
    fun homeToSearchToAnswerFlowRendersAnswerAndDismissedHazardStaysDismissedAfterBackNavigation() {
        val contentStore = freshContentStore()
        lateinit var navController: NavHostController

        compose.setContent {
            navController = rememberNavController()
            SheltrNavHost(
                contentStore = contentStore,
                settingsStore = InMemorySettingsStore(),
                retriever = Retriever(EmptyStoreReader()),
                syncEngine = { error("not expected to be called in this test") },
                publisherBaseUrl = null,
                publisherPublicKey = null,
                onSettingsChanged = {},
                navController = navController,
            )
        }

        val floodLabel = compose.activity.getString(R.string.hazard_flood)

        // Home -> tap a hazard -> Search pre-filled with that hazard.
        compose.onNodeWithTag("home-hazard-grid").performScrollToNode(hasText(floodLabel))
        compose.onNodeWithText(floodLabel).performClick()
        compose.onNodeWithTag("search-screen").assertExists()
        compose.onNodeWithText(floodLabel).assertExists()

        // Dismiss the pre-filled hazard chip, then type a query and submit.
        compose.onNodeWithTag("search-hazard-chip").performClick()
        compose.onNodeWithText(floodLabel).assertDoesNotExist()
        compose.onNodeWithTag("search-query-field").performTextInput("flood shelter")
        compose.onNodeWithTag("search-submit-button").performClick()

        // Search -> submit -> Answer renders. Polled, not a direct assertExists(): SearchViewModel's retrieval
        // runs on the real production Dispatchers.IO (a real background thread, unlike this app's other tests,
        // which all inject a TestDispatcher for exactly this reason), so the result/navigation callback lands
        // asynchronously rather than within performClick()'s own call stack.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("answer-screen").fetchSemanticsNodes().isNotEmpty()
        }

        // Back-navigation to Search (same NavController, same back stack entry/ViewModel) must not resurrect
        // the dismissed hazard chip -- Finding 2's regression.
        compose.runOnIdle { navController.popBackStack() }
        compose.onNodeWithTag("search-screen").assertExists()
        compose.onNodeWithText(floodLabel).assertDoesNotExist()
    }
}
