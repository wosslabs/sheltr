package com.sheltr.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sheltr.shared.retrieval.Retriever
import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.SettingsStore
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.sync.SyncEngine

/** The app's four top-level destinations. [path] is the navigation-compose route string. */
sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Search : Route("search")
    data object Answer : Route("answer")
    data object Settings : Route("settings")
}

/**
 * The app's one [NavHost]. Dependencies are passed down from [com.sheltr.android.MainActivity] (ultimately built
 * once in [com.sheltr.android.SheltrApplication.onCreate]), not re-fetched from a singleton inside each screen,
 * so screens stay testable with fakes (see SheltrNavHostTest).
 *
 * Every route below is a placeholder stub (a tagged, empty [Box]); Tasks 2-5 of this plan replace each one with
 * its real screen, reading whichever of these dependencies that screen needs.
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
            Box(Modifier.testTag("home-screen"))
        }
        composable(Route.Search.path) {
            Box(Modifier.testTag("search-screen"))
        }
        composable(Route.Answer.path) {
            Box(Modifier.testTag("answer-screen"))
        }
        composable(Route.Settings.path) {
            Box(Modifier.testTag("settings-screen"))
        }
    }
}
