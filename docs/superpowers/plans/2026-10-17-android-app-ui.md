# Android App UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Sheltr's first mobile UI — a new `apps/mobile/android` Jetpack Compose app with four screens (Home, Search, Answer, Settings), simple and icon-led, backed entirely by the existing `apps/mobile/shared` module's ports, accessible (TTS read-aloud, large text, high contrast, TalkBack labels), and usable fully offline from first launch via the bundled sample corpus.

**Architecture:** One Compose `NavHost` with four routes. Each screen has a small `ViewModel` that talks only to `apps/mobile/shared`'s public ports (`Retriever`, `ContentStore` as `StoreReader`/`SyncStateStore`, `SettingsStore`, `SyncEngine`) — never to SQLDelight or the database directly, preserving the hexagonal boundary the shared module already establishes. `SheltrApplication` builds the one `ContentStore`/`AndroidSettingsStore`/`Retriever` instance set (manual constructor wiring, no DI framework — four screens doesn't justify one) and installs the sample bundle on first run. No accounts, no analytics, no server call except an explicitly-labeled, optional "sync now" that no-ops when no bundle host is configured (none is, yet).

**Tech Stack:** Jetpack Compose (Material3), Navigation Compose, AndroidX lifecycle ViewModels, AndroidX appcompat (per-app language API only), Robolectric for buildable/runnable tests without an emulator.

**Spec:** No separate design doc — decisions below were made in conversation. Depends on `docs/superpowers/plans/2026-10-17-android-platform-adapters.md` (androidTarget, `AndroidStoreDriverFactory`, `AndroidSettingsStore`, `AppSettings.highContrast`, the sample bundle asset) being implemented first.

## Global Constraints

- `minSdk = 24`, `compileSdk = 34` (match the shared module's Task 1 in the platform-adapters plan).
- No icon-only interactive element anywhere — every tappable control has a visible text label in addition to any icon, and a TalkBack `contentDescription`.
- Every tappable control is at least 48dp in both dimensions (hazard grid tiles use 64dp, comfortably above the minimum, since they are the primary navigation surface).
- UI chrome text comes only from Android string resources (`strings.xml`), never hardcoded in Kotlin — this is what makes Task 6's translations possible without touching every screen's code again.
- The shared module's `RetrievalResult.Refusal.MESSAGE` (English, hardcoded in `apps/mobile/shared`) is never shown to the user directly — the Android layer always substitutes its own localized string resource when it sees `RetrievalResult.Refusal`, so a Spanish-language user sees a Spanish refusal message, not an English one.
- Corpus content itself (passage text, document titles, agency names) is English-only in this slice and is never translated or altered — the language picker in Settings affects only UI chrome, and Settings must say so explicitly so users aren't misled into expecting translated guidance.
- No network call happens anywhere except inside the explicit "sync now" action, and that action must degrade to a clear "not available in this build" message (not a crash, not a silent no-op) when no bundle host URL is configured — which is the case for every build until a separate, future hosting plan exists.

## Review Focus

- Launching the app for the very first time, with no prior install and no network, must reach a working Home screen with real hazard tiles and a usable search — not a blank/crashed screen — because the sample bundle installs synchronously-enough-to-matter before Home renders its empty/ready state.
- A search query that matches nothing (genuine `RetrievalResult.Refusal`, e.g. "what should I do about my car insurance") must render the refusal screen cleanly, not an empty list or a crash — this is a core product promise ("refuses when no source passes its relevance check, rather than inventing guidance"), not an edge case to skip.
- Rotating the device or backgrounding the app mid-search (process death / Activity recreation) must not lose the in-flight query or crash — ViewModels must survive configuration change via the standard `ViewModel` lifecycle, and Answer screen state must not depend on an argument that disappears on recreation.
- A user with system font scale set to its maximum (Android's "largest" accessibility text size, 200%) combined with this app's own "extra large" text setting must not visually clip or overlap any screen — the two multipliers compose, so this combination is the real stress case, not just this app's setting alone.
- TTS is not available on every device/locale (engine missing, language data not installed) — `TextToSpeech`'s init callback can report `TextToSpeech.ERROR`, and the Answer screen's "read aloud" button must handle that by disabling itself or showing a message, not by silently doing nothing or crashing on `speak()`.

---

### Task 1: App module scaffold, DI, theme, navigation shell

**Files:**
- Modify: `settings.gradle.kts` (add `include(":apps:mobile:android")`)
- Create: `apps/mobile/android/build.gradle.kts`
- Create: `apps/mobile/android/src/main/AndroidManifest.xml`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/SheltrApplication.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/MainActivity.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/theme/Theme.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/theme/TextScale.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/SheltrNavHost.kt`
- Create: `apps/mobile/android/src/main/res/values/strings.xml` (seed with `app_name` only; each later task adds its own screen's entries to this same file)
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/SheltrNavHostTest.kt`

**Interfaces:**
- Produces: `class SheltrApplication : Application()` exposing `val contentStore: ContentStore`, `val settingsStore: SettingsStore`, `val retriever: Retriever` (built from `contentStore`), `val syncEngine: SyncEngine`, `val publisherBaseUrl: String?` and `val publisherPublicKey: ByteArray?` (both from `BuildConfig`, `null`/empty in every build until hosting exists — Task 5's `SettingsViewModel` consumes both) as `val`s computed once in `onCreate()`, plus running `installSampleBundleIfEmpty(contentStore, ...)` (from the platform-adapters plan's `AssetBundleInstaller`) off the main thread before any screen can read `contentStore`.
- Produces: `sealed class Route` (or string-constant route names) for `Home`, `Search`, `Answer`, `Settings`, consumed by every later task's screen.
- Produces: `enum class ContrastMode { NORMAL, HIGH }` and a `@Composable fun SheltrTheme(highContrast: Boolean, textScale: TextScale, content: @Composable () -> Unit)` wrapping Material3 `MaterialTheme` with two `ColorScheme`s and a `LocalDensity` override combining the OS font scale with `TextScale`'s multiplier (`NORMAL` = 1.0, `LARGE` = 1.25, `EXTRA_LARGE` = 1.6 — multiplied with, not replacing, `LocalDensity.current.fontScale`).

- [ ] **Step 1: Write the Gradle module and manifest**

`apps/mobile/android/build.gradle.kts`: `com.android.application` plugin, `namespace = "com.sheltr.android"`, `applicationId = "com.sheltr.android"`, `minSdk = 24`, `compileSdk = 34`, `targetSdk = 34`, Compose enabled (`buildFeatures { compose = true }`), dependency on `project(":apps:mobile:shared")`, Compose BOM + `ui`, `material3`, `activity-compose`, `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.appcompat:appcompat` (per-app language support, Task 6), `org.jetbrains.kotlinx:kotlinx-coroutines-android`; `testImplementation` JUnit, Robolectric, `androidx.compose.ui:ui-test-junit4`, `androidx.test:core`. `AndroidManifest.xml`: one `<activity>` (`MainActivity`, exported, main/launcher intent filter), `android:name=".SheltrApplication"` on `<application>`.

- [ ] **Step 2: Write a failing navigation smoke test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class SheltrNavHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun startsOnHomeRoute() {
        compose.setContent { SheltrNavHost(/* fake dependencies */) }
        compose.onNodeWithTag("home-screen").assertExists()
    }
}
```

- [ ] **Step 3: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: FAIL (module/classes don't exist yet).

- [ ] **Step 4: Implement `SheltrApplication`, `Theme.kt`, `TextScale.kt`, `SheltrNavHost`, `MainActivity`**

`SheltrNavHost` takes the dependencies `SheltrApplication` exposes (passed down from `MainActivity`, not re-fetched from a singleton inside each screen — keeps screens testable with fakes) and defines the four routes as placeholder `Box(Modifier.testTag("home-screen"))`-style stubs for now; later tasks replace each stub with the real screen. `MainActivity` reads `AppSettings` once via `(application as SheltrApplication).settingsStore.get()` to pick `highContrast`/`textScale` for `SheltrTheme`, and re-reads it whenever Settings reports a change (Task 5 wires the actual recomposition trigger — a `mutableStateOf<AppSettings>` hoisted in `MainActivity` that Settings' ViewModel updates through a callback is sufficient; no need for a reactive settings `Flow` in this slice, since `SettingsStore` is a synchronous port).

- [ ] **Step 5: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts apps/mobile/android
git commit -m "feat(mobile): scaffold Android app module with navigation and theme"
```

---

### Task 2: Home screen

**Files:**
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/home/HomeScreen.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/home/HomeViewModel.kt`
- Create: `apps/mobile/android/src/main/res/drawable/ic_hazard_*.xml` (twelve simple single-color vector icons, one per `DisasterType`: `ic_hazard_earthquake`, `ic_hazard_flood`, `ic_hazard_tropical_cyclone`, `ic_hazard_tornado`, `ic_hazard_tsunami`, `ic_hazard_wildfire`, `ic_hazard_extreme_heat`, `ic_hazard_winter_storm`, `ic_hazard_landslide`, `ic_hazard_glacial_lake_flood`, `ic_hazard_volcano`, `ic_hazard_drought` — simple geometric glyphs, e.g. a zigzag crack for earthquake, a wave for tsunami, a snowflake for winter storm; exact path data is this task's own call, not determined by any test)
- Modify: `apps/mobile/android/src/main/res/values/strings.xml` (add `home_title`, `home_search_placeholder`, `home_search_hint_description`, and one `hazard_<name>` label per hazard, e.g. `hazard_earthquake` = "Earthquake")
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/home/HomeScreenTest.kt`
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `Route` (Task 1).
- Produces: `data class HazardTile(val hazard: String, @DrawableRes val icon: Int, @StringRes val label: Int)` (the fixed list of 12, one per `DisasterType` name), `class HomeViewModel : ViewModel()` exposing `val hazardTiles: List<HazardTile>` and `fun onSearchSubmit(text: String)` / `fun onHazardTapped(hazard: String)` as callbacks the screen wires to navigation (navigating is the screen's job via the `NavController` it's given; the ViewModel only validates/trims the text and reports "nothing to search" for blank input).

- [ ] **Step 1: Write failing `HomeViewModelTest`**

```kotlin
@Test fun hazardTilesCoverAllTwelveDisasterTypes() {
    assertEquals(12, HomeViewModel().hazardTiles.size)
    assertTrue(HomeViewModel().hazardTiles.any { it.hazard == "EARTHQUAKE" })
}
@Test fun blankSearchSubmitDoesNothing() {
    var called = false
    val vm = HomeViewModel()
    vm.onSearchSubmit("   ") { called = true }
    assertFalse(called)
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `HomeViewModel`**

`hazardTiles` is a fixed, hand-written list of the 12 `HazardTile`s (same hazard-name strings as `tools/publisher`'s `DisasterType` enum — copy the twelve names, same source-of-truth note as `HazardVocabulary` in the retrieval plan). `onSearchSubmit(text, onNavigate: (String) -> Unit)` trims `text`; if non-blank, calls `onNavigate(trimmed)`.

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Write failing `HomeScreenTest`**

```kotlin
@Test fun showsAllHazardTilesWithLabelAndIcon() {
    compose.setContent { HomeScreen(onHazardTapped = {}, onSearchSubmit = {}) }
    compose.onNodeWithText("Earthquake").assertExists()
}
@Test fun everyHazardTileHasAContentDescription() {
    // assert no tile is icon-only: each tile's semantics has a non-empty content description equal to its label
}
```

- [ ] **Step 6: Run — expect FAIL**

- [ ] **Step 7: Implement `HomeScreen`**

A `LazyVerticalGrid` of 64dp-minimum tiles, each showing the hazard's icon and localized label (text, not icon-only — satisfies the Global Constraint), plus a `TextField`/search bar above the grid with `home_search_placeholder` and an IME "search" action calling `onSearchSubmit`. Tag the screen root `Modifier.testTag("home-screen")` (satisfies Task 1's smoke test) and wire it into `SheltrNavHost` in place of the Task 1 stub.

- [ ] **Step 8: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/home apps/mobile/android/src/main/res/drawable apps/mobile/android/src/main/res/values/strings.xml apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/home
git commit -m "feat(mobile): add Home screen with hazard grid and quick search"
```

---

### Task 3: Search screen

**Files:**
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/search/SearchScreen.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/search/SearchViewModel.kt`
- Modify: `apps/mobile/android/src/main/res/values/strings.xml` (add `search_title`, `search_placeholder`, `search_active_hazard_chip_description`, `search_submit_description`)
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/search/SearchViewModelTest.kt`
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/search/SearchScreenTest.kt`

**Interfaces:**
- Consumes: `Retriever`, `RetrievalQuery`, `RetrievalResult` (`apps/mobile/shared`), `SettingsStore` (for the user's `country`).
- Produces: `class SearchViewModel(private val retriever: Retriever, private val country: String?) : ViewModel()` with `val query: StateFlow<String>`, `val activeHazard: StateFlow<String?>`, `fun onQueryChange(text: String)`, `fun onHazardSelected(hazard: String?)`, `fun onSubmit(onNavigateToAnswer: (RetrievalResult) -> Unit)` which calls `retriever.retrieve(RetrievalQuery(query.value, country, activeHazard.value?.let { setOf(it) } ?: emptySet()))` on `Dispatchers.IO` inside `viewModelScope.launch` (the shared module's `Retriever`/`ContentStore` calls are synchronous SQLite I/O and must not run on the main thread).

- [ ] **Step 1: Write failing `SearchViewModelTest`**

```kotlin
@Test fun submitCallsRetrieverWithCurrentQueryAndHazardAndCountry() = runTest {
    val retriever = FakeRetriever(result = RetrievalResult.Refusal)
    val vm = SearchViewModel(retriever, country = "KE")
    vm.onQueryChange("flood shelter")
    vm.onHazardSelected("FLOOD")
    var navigated: RetrievalResult? = null
    vm.onSubmit { navigated = it }
    advanceUntilIdle()
    assertEquals("flood shelter", retriever.lastQuery?.text)
    assertEquals(setOf("FLOOD"), retriever.lastQuery?.activeHazards)
    assertEquals("KE", retriever.lastQuery?.country)
    assertEquals(RetrievalResult.Refusal, navigated)
}
```

(`FakeRetriever` is a tiny test double built around a real `Retriever` or a hand-rolled subclass/interface seam — if `Retriever` is a concrete `class` with no interface, add a thin `interface RetrieverPort { fun retrieve(query: RetrievalQuery): RetrievalResult }` in this app module that `Retriever` already satisfies structurally, and have `SearchViewModel` depend on that port instead of the concrete class, purely so this ViewModel is unit-testable without a real `StoreReader`. This is a small, justified seam at the UI boundary, not a change to the shared module.)

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `SearchViewModel`**

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Write failing `SearchScreenTest`**

```kotlin
@Test fun typingAndSubmittingTriggersNavigation() { /* type into the field, tap the submit button, assert the navigation callback fired */ }
@Test fun hazardChipIsOptionalAndClearable() { /* select a hazard, then clear it, assert state reflects null */ }
```

- [ ] **Step 6: Run — expect FAIL**

- [ ] **Step 7: Implement `SearchScreen`**

Large text field pre-focused on entry (so typing starts immediately — matches "very simple to follow": the next thing to do is always obvious), an optional dismissible hazard chip shown only when the screen was entered from a Home hazard tap, and a clearly labeled submit button (text + icon, per the Global Constraint). On submit's navigation callback, navigate to the Answer route carrying the `RetrievalResult` (via a shared `ViewModel`-held result the Answer screen reads — Compose Navigation can't put a `RetrievalResult` in a type-safe nav argument directly; have `SearchViewModel` hand the result to a small app-scoped holder, e.g. a `NavBackStackEntry`-scoped shared ViewModel or an in-memory single-slot holder keyed by a generated id passed as the nav argument — this task's own call for which, consistent with Task 1's "no reactive settings Flow needed" level of simplicity).

- [ ] **Step 8: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/search apps/mobile/android/src/main/res/values/strings.xml apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/search
git commit -m "feat(mobile): add Search screen wired to the shared Retriever"
```

---

### Task 4: Answer screen

**Files:**
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/answer/AnswerScreen.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/answer/AnswerViewModel.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/answer/TextToSpeechController.kt`
- Modify: `apps/mobile/android/src/main/res/values/strings.xml` (add `answer_source_label`, `answer_read_aloud_description`, `answer_read_aloud_unavailable`, `answer_refusal_title`, `answer_refusal_body` — the localized refusal message, a Sheltr-written equivalent of the shared module's English `RetrievalResult.Refusal.MESSAGE`, not a copy of it)
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/answer/AnswerScreenTest.kt`
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/answer/TextToSpeechControllerTest.kt`

**Interfaces:**
- Consumes: `RetrievalResult`, `Passage`, `Citation.of(passage)` (`apps/mobile/shared`).
- Produces: `class TextToSpeechController(context: Context) { val isAvailable: StateFlow<Boolean>; fun speak(text: String); fun stop(); fun shutdown() }`, wrapping `android.speech.tts.TextToSpeech`, forcing `language = Locale.ENGLISH` regardless of app UI locale (per the Global Constraint — corpus content is English-only), setting `isAvailable` to `false` if the init callback reports anything other than `TextToSpeech.SUCCESS` or if `setLanguage(Locale.ENGLISH)` returns `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED`.

- [ ] **Step 1: Write failing `TextToSpeechControllerTest`**

```kotlin
@Test fun isAvailableFalseWhenEngineInitFails() {
    // Robolectric's shadow TextToSpeech can be driven to report init failure; assert isAvailable.value == false
    // and that speak() after that is a safe no-op, not a crash.
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `TextToSpeechController`**

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Write failing `AnswerScreenTest`**

```kotlin
@Test fun refusalShowsLocalizedRefusalMessageNotTheSharedModuleEnglishConstant() {
    compose.setContent { AnswerScreen(result = RetrievalResult.Refusal, onReadAloud = {}, readAloudAvailable = true) }
    compose.onNodeWithText(RetrievalResult.Refusal.MESSAGE).assertDoesNotExist()
    compose.onNodeWithText(/* the string-resource value of answer_refusal_body in the test's locale */).assertExists()
}
@Test fun passagesShowCitationSourceAndReadAloudButton() {
    val result = RetrievalResult.Passages(listOf(/* a fixture Passage */))
    compose.setContent { AnswerScreen(result = result, onReadAloud = {}, readAloudAvailable = true) }
    compose.onNodeWithText(/* fixture document's title */).assertExists()
}
@Test fun readAloudButtonDisabledWhenTtsUnavailable() {
    compose.setContent { AnswerScreen(result = /* a Passages fixture */, onReadAloud = {}, readAloudAvailable = false) }
    compose.onNodeWithContentDescription(/* answer_read_aloud_description value */).assertIsNotEnabled()
}
```

- [ ] **Step 6: Run — expect FAIL**

- [ ] **Step 7: Implement `AnswerScreen` and `AnswerViewModel`**

`AnswerViewModel` holds the `RetrievalResult` handed from Search (Task 3's holder), exposes `readAloudAvailable: StateFlow<Boolean>` from `TextToSpeechController.isAvailable`, and `fun onReadAloudTapped()` calling `controller.speak(passage.chunk.content)` for the top passage. `AnswerScreen` renders: on `Passages`, each passage's `chunk.content` as the primary text, then its `Citation.of(passage)` as a visually distinct source block (`answer_source_label` + document title + agency + `publishedDate` + a clickable `sourceUri` link + `pageNumber` if present), and the read-aloud button (text + icon, disabled with `answer_read_aloud_unavailable` shown when `readAloudAvailable` is false — never hidden, so the user understands why it's missing, which also satisfies the plan's TTS Review Focus item). On `Refusal`, renders `answer_refusal_title`/`answer_refusal_body` only — explicitly never `RetrievalResult.Refusal.MESSAGE`.

- [ ] **Step 8: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/answer apps/mobile/android/src/main/res/values/strings.xml apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/answer
git commit -m "feat(mobile): add Answer screen with citations, refusal state and read-aloud"
```

---

### Task 5: Settings screen

**Files:**
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/settings/SettingsScreen.kt`
- Create: `apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/settings/SettingsViewModel.kt`
- Modify: `apps/mobile/android/src/main/res/values/strings.xml` (add `settings_title`, `settings_country_label`, `settings_language_label`, `settings_language_notice` — states plainly that this only changes menus/labels, not guidance content — `settings_text_size_label`, `settings_high_contrast_label`, `settings_sync_now_label`, `settings_last_sync_label`, `settings_sync_unavailable_message`)
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/settings/SettingsViewModelTest.kt`
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `AppSettings`, `TextScale`, `SyncEngine`, `SyncStateStore` (all `apps/mobile/shared`), `AppCompatDelegate`/`LocaleListCompat` (AndroidX).
- Produces: `class SettingsViewModel(private val settingsStore: SettingsStore, private val syncEngine: SyncEngine, private val store: SyncStateStore, private val baseUrl: String?, private val publicKey: ByteArray?) : ViewModel()` exposing `val settings: StateFlow<AppSettings>`, `fun onCountryChanged(code: String?)`, `fun onTextScaleChanged(scale: TextScale)`, `fun onHighContrastChanged(enabled: Boolean)` (each persists via `settingsStore.put` and updates `settings`), `fun onSyncNowTapped(onResult: (String) -> Unit)` (calls `onResult` with the `settings_sync_unavailable_message` string key immediately, without calling `syncEngine.run`, when `baseUrl` is null/blank; otherwise launches the sync on `Dispatchers.IO` and reports the `SyncResult`).

- [ ] **Step 1: Write failing `SettingsViewModelTest`**

```kotlin
@Test fun changingCountryPersistsThroughTheStore() {
    val store = InMemorySettingsStore()
    val vm = SettingsViewModel(store, fakeSyncEngine, fakeSyncStateStore, baseUrl = null, publicKey = null)
    vm.onCountryChanged("KE")
    assertEquals("KE", store.get().country)
    assertEquals("KE", vm.settings.value.country)
}
@Test fun syncNowReportsUnavailableWhenNoBaseUrlConfigured() {
    val vm = SettingsViewModel(InMemorySettingsStore(), fakeSyncEngine, fakeSyncStateStore, baseUrl = null, publicKey = null)
    var message: String? = null
    vm.onSyncNowTapped { message = it }
    assertEquals("settings_sync_unavailable_message", message) // or the resolved string, per the test's chosen seam
    assertFalse(fakeSyncEngine.wasCalled)
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `SettingsViewModel`**

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Write failing `SettingsScreenTest`**

```kotlin
@Test fun countryPickerListsRealCountryNamesInCurrentLocale() {
    // Built from java.util.Locale.getISOCountries() + Locale("", code).getDisplayCountry(currentLocale) —
    // assert at least one known country name (e.g. "Kenya") appears in the picker's options.
}
@Test fun highContrastToggleIsLabeledNotIconOnly() { /* assert settings_high_contrast_label text exists next to the switch */ }
```

- [ ] **Step 6: Run — expect FAIL**

- [ ] **Step 7: Implement `SettingsScreen`**

Country picker: a searchable list built from `Locale.getISOCountries()`, each entry's display name via `Locale("", code).getDisplayCountry(currentAppLocale)`, selecting one calls `onCountryChanged`. Language picker: a fixed four-option list (System default, English, Español, Français) that calls `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))` directly (`""` tag for "system default") — this is Android/AppCompat's own per-app language mechanism, which persists itself; it is deliberately not routed through `SettingsStore`/`AppSettings`, since it's a presentation-only concern with no effect on retrieval or boosting (document this choice inline with a short comment, since it's the one setting that doesn't follow the "everything in `AppSettings`" pattern the other three do). Show `settings_language_notice` directly under the language picker, always visible, not just on change. Text size: three-option selector mapped to `TextScale`. High contrast: a labeled switch. Sync now: a button showing `settings_last_sync_label` + a human-readable time (or "never") from `store.lastSyncEpochSeconds()`, and on tap either runs the sync (showing a progress state while `viewModelScope.launch` is in flight) or shows `settings_sync_unavailable_message` as a dismissible inline message, per the ViewModel's behavior.

- [ ] **Step 8: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/mobile/android/src/main/kotlin/com/sheltr/android/ui/settings apps/mobile/android/src/main/res/values/strings.xml apps/mobile/android/src/test/kotlin/com/sheltr/android/ui/settings
git commit -m "feat(mobile): add Settings screen with country, language, text size, high contrast and sync"
```

---

### Task 6: Spanish and French translations

**Files:**
- Create: `apps/mobile/android/src/main/res/values-es/strings.xml`
- Create: `apps/mobile/android/src/main/res/values-fr/strings.xml`
- Test: `apps/mobile/android/src/test/kotlin/com/sheltr/android/StringResourceParityTest.kt`

**Interfaces:**
- Consumes: the complete `values/strings.xml` key set written across Tasks 2–5 (this task adds no new keys, which is why it runs last).

- [ ] **Step 1: Write a failing resource-parity test**

```kotlin
@Test fun everyDefaultStringKeyExistsInEverySupportedLocale() {
    // Parse res/values/strings.xml, res/values-es/strings.xml, res/values-fr/strings.xml (simple XML
    // parsing of <string name="..."> elements) and assert the es and fr key sets are identical to the
    // default's — catches a translation silently falling back to English rather than missing on purpose.
}
```

- [ ] **Step 2: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest --tests "*.StringResourceParityTest"`
Expected: FAIL (the two locale files don't exist yet).

- [ ] **Step 3: Translate every key from `values/strings.xml` into `values-es/strings.xml` and `values-fr/strings.xml`**

Every key written in Tasks 2 (Home, including all twelve `hazard_*` labels), 3 (Search), 4 (Answer, including `answer_refusal_body` — write a genuine Spanish/French refusal sentence, not a machine-literal translation of the English one, so it reads naturally), and 5 (Settings, including `settings_language_notice`, which itself must say — in Spanish, in the Spanish file; in French, in the French file — that this setting does not translate guidance content).

- [ ] **Step 4: Run**

Run: `gradle :apps:mobile:android:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Manually verify the language picker end-to-end**

Since this plan's environment has no emulator (Robolectric tests run headless JVM, not a real device), this step is a note for whoever runs the app on a device/emulator later: switch the in-app language picker through all four options and confirm every screen's text changes and no layout breaks under the longer French strings (French UI text commonly runs 15–20% longer than English — if a label truncates or wraps awkwardly, that's a real finding for a follow-up fix, not a reason to fail this task, since it cannot be verified in this headless environment).

- [ ] **Step 6: Commit**

```bash
git add apps/mobile/android/src/main/res/values-es apps/mobile/android/src/main/res/values-fr \
        apps/mobile/android/src/test/kotlin/com/sheltr/android/StringResourceParityTest.kt
git commit -m "feat(mobile): add Spanish and French UI translations"
```
