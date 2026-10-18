package com.sheltr.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sheltr.android.ui.home.HomeScreen
import com.sheltr.android.ui.search.RetrieverPort
import com.sheltr.android.ui.search.SearchScreen
import com.sheltr.android.ui.search.SearchViewModel
import com.sheltr.shared.retrieval.Retriever
import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.SettingsStore
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.sync.SyncEngine

/** The app's four top-level destinations. [path] is the navigation-compose route string. */
sealed class Route(val path: String) {
    data object Home : Route("home")

    /**
     * Registered with `hazard`/`query` as query parameters (`?key={key}`, not path segments), which Navigation
     * Compose always treats as optional/nullable -- navigating to plain `"search"` (no params) still matches
     * this same route, and `backStackEntry.arguments?.getString(...)` returns null for whichever one wasn't
     * supplied. This lets Home (Task 2) carry an optional pre-selected hazard and/or pre-filled search text
     * into Search (Task 3) without Task 3 needing its own separate route.
     */
    data object Search : Route("search?hazard={hazard}&query={query}") {
        /**
         * Builds the concrete string passed to `NavController.navigate` for this route. [hazard] is a
         * `DisasterType` name (see `HomeViewModel.HazardTile`'s `hazard` field); [query] is pre-filled search
         * text. Both null navigates to a blank Search screen. Values are URL-encoded since free-text search
         * terms can contain spaces and other characters the route string can't carry raw.
         */
        fun destination(hazard: String? = null, query: String? = null): String {
            val params = buildList {
                hazard?.let { add("hazard=${Uri.encode(it)}") }
                query?.let { add("query=${Uri.encode(it)}") }
            }
            return if (params.isEmpty()) "search" else "search?${params.joinToString("&")}"
        }
    }
    /**
     * `resultId` carries the key into [RetrievalResultHolder] for the
     * [com.sheltr.shared.retrieval.RetrievalResult] Search retrieved -- not the result itself, which can't be
     * a type-safe nav argument (Bundle-backed arguments can't carry a non-Parcelable domain type; see
     * [RetrievalResultHolder]'s doc comment for the full design this task, Task 3, built for Task 4 to
     * consume: `AnswerViewModel` reads it with `RetrievalResultHolder.take(resultId)`).
     *
     * Unlike Search's `hazard`/`query`, `resultId` is a mandatory path segment, not an optional query
     * parameter: Answer is only ever reached via this handoff (see the Search composable below), so there is
     * no "blank" Answer route to support.
     */
    data object Answer : Route("answer/{resultId}") {
        fun destination(resultId: String): String = "answer/${Uri.encode(resultId)}"
    }
    data object Settings : Route("settings")
}

/**
 * The app's one [NavHost]. Dependencies are passed down from [com.sheltr.android.MainActivity] (ultimately built
 * once in [com.sheltr.android.SheltrApplication.onCreate]), not re-fetched from a singleton inside each screen,
 * so screens stay testable with fakes (see SheltrNavHostTest).
 *
 * Home (Task 2) and Search (Task 3) are now the real [HomeScreen]/`SearchScreen`; Answer/Settings below remain
 * placeholder stubs (a tagged, empty [Box]) until Tasks 4-5 of this plan replace each with its real screen,
 * reading whichever of these dependencies that screen needs.
 *
 * Search hands its [com.sheltr.shared.retrieval.RetrievalResult] to Answer via [RetrievalResultHolder]: the
 * Search composable below stores the result and navigates with the id [RetrievalResultHolder.put] returns, as
 * `Route.Answer.destination(resultId)`. See [RetrievalResultHolder]'s doc comment for why (Navigation Compose
 * arguments can't carry a non-Parcelable domain type) and [Route.Answer]'s for the route shape this produces.
 *
 * [onSettingsChanged] is the callback Task 5's Settings screen calls after writing new [AppSettings], so
 * [com.sheltr.android.MainActivity] can update the [SheltrTheme][com.sheltr.android.ui.theme.SheltrTheme]
 * parameters it hoists. [syncEngine] is a provider, not the engine itself:
 * [com.sheltr.android.SheltrApplication.syncEngine] is built lazily to avoid constructing an HTTP client (and
 * resolving its engine from the classpath) before anything needs it, and taking the realized [SyncEngine] as a
 * parameter here would force that lazy on every call site regardless of whether a screen ever syncs.
 */
@Composable
fun SheltrNavHost(
    contentStore: ContentStore,
    settingsStore: SettingsStore,
    retriever: Retriever,
    syncEngine: () -> SyncEngine,
    publisherBaseUrl: String?,
    publisherPublicKey: ByteArray?,
    onSettingsChanged: (AppSettings) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Route.Home.path) {
        composable(Route.Home.path) {
            HomeScreen(
                onHazardTapped = { hazard -> navController.navigate(Route.Search.destination(hazard = hazard)) },
                onSearchSubmit = { text -> navController.navigate(Route.Search.destination(query = text)) },
            )
        }
        composable(Route.Search.path) { backStackEntry ->
            SearchScreen(
                onNavigateToAnswer = { result ->
                    val resultId = RetrievalResultHolder.put(result)
                    navController.navigate(Route.Answer.destination(resultId))
                },
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SearchViewModel(
                                retriever = RetrieverPort(retriever::retrieve),
                                country = settingsStore.get().country,
                            )
                        }
                    },
                ),
                initialHazard = backStackEntry.arguments?.getString("hazard"),
                initialQuery = backStackEntry.arguments?.getString("query"),
            )
        }
        composable(Route.Answer.path) {
            Box(Modifier.testTag("answer-screen"))
        }
        composable(Route.Settings.path) {
            Box(Modifier.testTag("settings-screen"))
        }
    }
}
