package com.sheltr.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sheltr.android.ui.about.AboutScreen
import com.sheltr.android.ui.answer.AnswerScreen
import com.sheltr.android.ui.answer.AnswerViewModel
import com.sheltr.android.ui.answer.TextToSpeechController
import com.sheltr.android.ui.home.HomeScreen
import com.sheltr.android.ui.search.RetrieverPort
import com.sheltr.android.ui.search.SearchScreen
import com.sheltr.android.ui.search.SearchViewModel
import com.sheltr.android.ui.settings.SettingsScreen
import com.sheltr.android.ui.settings.SettingsViewModel
import com.sheltr.shared.retrieval.Retriever
import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.SettingsStore
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.StoredDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * `text`/`hazard` are *also* carried here, alongside `resultId`, even though they are redundant with what
     * [RetrievalResultHolder] already holds in the common case. They are not redundant in the case that
     * matters: Navigation Compose's back stack (route + all its arguments, `text`/`hazard` included) survives
     * process death via `SavedStateRegistry`, but [RetrievalResultHolder]'s in-memory map does not -- so after
     * a process restart, Answer composes again with the *same* `resultId`, `take(resultId)` correctly returns
     * `null` (a fresh process never put anything under that id), and `text`/`hazard` are the only way left to
     * answer the same question again. See [RetrievalResultHolder]'s doc comment for the full reasoning and the
     * exact fallback contract Task 4's `AnswerViewModel` must implement on that `null`.
     *
     * Unlike Search's `hazard`/`query` (both genuinely optional), `text` here is always supplied by the one
     * caller that builds this route (the Search composable below) -- `SearchViewModel.onSubmit` never invokes
     * its callback with a blank query (see that method's blank-guard). Navigation Compose still parses it as a
     * nullable query parameter like every other one here (Task 2's established, verified behavior), so
     * `Route.Answer`'s composable below, and `AnswerViewModel` itself, still null-check it -- there is just
     * never a real path that reaches either with `text` actually absent.
     */
    data object Answer : Route("answer/{resultId}?text={text}&hazard={hazard}") {
        /** [text]/[hazard] are the exact inputs that produced the [resultId] result -- see the class doc comment. */
        fun destination(resultId: String, text: String, hazard: String?): String {
            val params = buildList {
                add("text=${Uri.encode(text)}")
                hazard?.let { add("hazard=${Uri.encode(it)}") }
            }
            return "answer/${Uri.encode(resultId)}?${params.joinToString("&")}"
        }
    }
    data object Settings : Route("settings")
    data object About : Route("about")
}

/**
 * The app's one [NavHost]. Dependencies are passed down from [com.sheltr.android.MainActivity] (ultimately built
 * once in [com.sheltr.android.SheltrApplication.onCreate]), not re-fetched from a singleton inside each screen,
 * so screens stay testable with fakes (see SheltrNavHostTest).
 *
 * Home (Task 2), Search (Task 3), Answer (Task 4) and Settings (Task 5) are now the real
 * [HomeScreen]/`SearchScreen`/`AnswerScreen`/`SettingsScreen`.
 *
 * Search hands its [com.sheltr.shared.retrieval.RetrievalResult] to Answer via [RetrievalResultHolder]: the
 * Search composable below stores the result and navigates with the id [RetrievalResultHolder.put] returns,
 * plus the exact text/hazard that produced it, as `Route.Answer.destination(resultId, text, hazard)`. The
 * text/hazard are not decoration -- [RetrievalResultHolder] is process memory only and does not survive
 * process death, while Navigation Compose's own back stack does, so Answer can be recomposed after a process
 * restart with a `resultId` this fresh process's holder has never seen. The Answer composable below reads
 * `resultId`/`text`/`hazard` straight off `backStackEntry.arguments` and hands them to `AnswerViewModel`, which
 * implements the `take()`-then-fallback recovery itself; see [RetrievalResultHolder]'s doc comment for the full
 * reasoning, and [Route.Answer]'s for the route shape this produces.
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
                onSettingsTapped = { navController.navigate(Route.Settings.path) },
            )
        }
        composable(Route.Search.path) { backStackEntry ->
            SearchScreen(
                onNavigateToAnswer = { result, submittedText, submittedHazard ->
                    val resultId = RetrievalResultHolder.put(result)
                    navController.navigate(Route.Answer.destination(resultId, submittedText, submittedHazard))
                },
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SearchViewModel(
                                retriever = RetrieverPort(retriever::retrieve),
                                country = settingsStore.get().country,
                                initialHazard = backStackEntry.arguments?.getString("hazard"),
                                initialQuery = backStackEntry.arguments?.getString("query"),
                            )
                        }
                    },
                ),
            )
        }
        composable(Route.Answer.path) { backStackEntry ->
            // applicationContext, not the Activity context LocalContext.current would otherwise hand us:
            // TextToSpeech retains whatever context it's constructed with for its whole lifetime (until
            // shutdown(), in AnswerViewModel.onCleared()), which can outlive this particular Activity instance
            // (e.g. a configuration change recreates the Activity while this nav back-stack entry, and the
            // ViewModel/TTS engine it owns, survive) -- an Activity context held that long is a leak.
            val context = LocalContext.current.applicationContext
            val resultId = backStackEntry.arguments?.getString("resultId")
            val text = backStackEntry.arguments?.getString("text")
            val hazard = backStackEntry.arguments?.getString("hazard")
            val viewModel: AnswerViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AnswerViewModel(
                            resultId = resultId,
                            text = text,
                            hazard = hazard,
                            retriever = RetrieverPort(retriever::retrieve),
                            country = settingsStore.get().country,
                            ttsController = TextToSpeechController(context),
                        )
                    }
                },
            )
            val result by viewModel.result.collectAsState()
            val readAloudAvailable by viewModel.readAloudAvailable.collectAsState()
            val currentResult = result
            if (currentResult != null) {
                AnswerScreen(
                    result = currentResult,
                    onReadAloud = viewModel::onReadAloudTapped,
                    readAloudAvailable = readAloudAvailable,
                )
            } else {
                // Transient: the take()-then-fallback retrieval (AnswerViewModel's init, see
                // RetrievalResultHolder's doc comment) is still in flight. Keeps the "answer-screen" tag
                // present even during this brief window, same as the stub this replaces.
                Box(Modifier.testTag("answer-screen"))
            }
        }
        composable(Route.Settings.path) {
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            settingsStore = settingsStore,
                            syncEngine = syncEngine,
                            store = contentStore,
                            baseUrl = publisherBaseUrl,
                            publicKey = publisherPublicKey,
                        )
                    }
                },
            )
            SettingsScreen(
                viewModel = viewModel,
                onSettingsChanged = onSettingsChanged,
                onAboutTapped = { navController.navigate(Route.About.path) },
            )
        }
        composable(Route.About.path) {
            // Read off the main thread: the document list comes from the SQLite content store.
            val documents by produceState(emptyList<StoredDocument>()) {
                value = withContext(Dispatchers.IO) { contentStore.documents() }
            }
            AboutScreen(documents = documents)
        }
    }
}
