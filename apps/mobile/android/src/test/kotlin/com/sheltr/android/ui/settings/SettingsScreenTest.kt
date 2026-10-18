package com.sheltr.android.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import com.sheltr.android.R
import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.InMemorySettingsStore
import com.sheltr.shared.settings.TextScale
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.StoreDriverFactory
import com.sheltr.shared.sync.SyncEngine
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Same technique as `SettingsViewModelTest.freshContentStore`: no content.db exists, so the driver is never opened. */
private fun freshContentStore(): ContentStore {
    val dir = Files.createTempDirectory("settings-screen-test").toFile()
    return ContentStore(
        dir.absolutePath,
        StoreDriverFactory { _, _ -> error("not expected to be called: no content.db exists in this fresh directory") },
    )
}

/**
 * `@Config(application = Application::class)`: same reasoning as `SheltrNavHostTest`/`AnswerScreenTest` -- avoids
 * instantiating the real `SheltrApplication` for a test that only exercises [SettingsScreen] in isolation.
 *
 * `sdk = [30]`: on API 33+, `AppCompatDelegate.setApplicationLocales` delegates to the real platform
 * `LocaleManager` system service instead of AppCompat's own pre-33 SharedPreferences-backed implementation;
 * Robolectric 4.17 does not back that service with working state, so the call silently no-ops under the
 * default (latest) simulated SDK. Pinning to 30 keeps these tests on AppCompat's own, Robolectric-friendly path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsStore: InMemorySettingsStore
    private lateinit var contentStore: ContentStore

    @Before
    fun setUp() {
        settingsStore = InMemorySettingsStore()
        contentStore = freshContentStore()
    }

    @After
    fun tearDown() {
        contentStore.close()
        // AppCompatDelegate's application-locale override is process-global state; reset it so a language
        // selection made by one test can never leak into the next test in this class (or another class sharing
        // this Robolectric process).
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    /**
     * `@Composable` + `remember`, not a plain function: `compose.setContent { ... }`'s content lambda can be
     * re-invoked by Compose across recompositions (e.g. the first idle pass after initial composition), and a
     * plain function call here would build a *new* [SettingsViewModel] -- with a fresh `settings`
     * [kotlinx.coroutines.flow.StateFlow] -- on each one, silently discarding whichever instance the screen's
     * widgets had already captured in their click handlers. `remember` keyed on [baseUrl]/[publicKey] (this
     * test's only varying inputs) keeps exactly one instance for the screen's lifetime, the same guarantee
     * `SheltrNavHost`'s real `viewModel { ... }` factory calls give every other screen in this app.
     */
    @Composable
    private fun viewModel(baseUrl: String? = null, publicKey: ByteArray? = null): SettingsViewModel =
        remember(baseUrl, publicKey) {
            SettingsViewModel(
                settingsStore = settingsStore,
                syncEngine = { SyncEngine({ error("not expected to be called in this test") }) },
                store = contentStore,
                baseUrl = baseUrl,
                publicKey = publicKey,
            )
        }

    @Test
    fun rootIsTaggedSettingsScreen() {
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-screen").assertExists()
    }

    @Test
    fun countryPickerListsRealCountryNamesInCurrentLocale() {
        // Built from java.util.Locale.getISOCountries() + Locale("", code).getDisplayCountry(currentLocale), not
        // a hardcoded list -- "Kenya" is one of ~250 real ISO country names that must appear once expanded.
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-country-selector").performClick()

        compose.onNodeWithText("Kenya").assertExists()
    }

    @Test
    fun selectingACountryOptionPersistsItAndCollapsesThePicker() {
        var changed: AppSettings? = null
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = { changed = it }) }

        compose.onNodeWithTag("settings-country-selector").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-country-option-KE").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("KE", settingsStore.get().country)
        assertEquals("KE", changed?.country)
        // The picker collapsed: its options are no longer in the tree.
        compose.onNodeWithTag("settings-country-option-KE").assertDoesNotExist()
    }

    @Test
    fun highContrastToggleIsLabeledNotIconOnly() {
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        val label = compose.activity.getString(R.string.settings_high_contrast_label)
        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithTag("settings-high-contrast-switch").assertExists()
    }

    @Test
    fun togglingHighContrastPersistsAndNotifiesOnSettingsChanged() {
        var changed: AppSettings? = null
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = { changed = it }) }

        compose.onNodeWithTag("settings-high-contrast-switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings-high-contrast-switch").performClick()

        compose.onNodeWithTag("settings-high-contrast-switch").assertIsOn()
        assertTrue(settingsStore.get().highContrast)
        assertTrue(changed?.highContrast ?: false)
    }

    @Test
    fun selectingATextSizeOptionPersistsAndUpdatesViewModel() {
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-text-size-LARGE").performScrollTo().performClick()

        assertEquals(TextScale.LARGE, settingsStore.get().textScale)
    }

    @Test
    fun languageNoticeIsAlwaysVisibleNotOnlyAfterAChange() {
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        val notice = compose.activity.getString(R.string.settings_language_notice)
        compose.onNodeWithText(notice).assertExists()
    }

    @Test
    fun selectingEnglishSetsApplicationLocalesDirectlyNotThroughSettingsStore() {
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-language-option-en").performScrollTo().performClick()

        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        // Deliberately not routed through SettingsStore/AppSettings: a presentation-only concern.
        assertEquals(AppSettings(), settingsStore.get())
    }

    @Test
    fun selectingALanguageOptionReportsItAsSelectedViaProperRadioSemantics() {
        // Regression coverage for review finding: the language option row uses Modifier.selectable (which
        // conveys Role.RadioButton + a selected/unselected state to TalkBack), not a bare Modifier.clickable.
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-language-option-en").performScrollTo().performClick()

        compose.onNodeWithTag("settings-language-option-en").assertIsSelected()
        compose.onNodeWithTag("settings-language-option-system").assertIsNotSelected()
    }

    @Test
    fun selectingSystemDefaultLanguageClearsTheApplicationLocaleOverride() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-language-option-system").performScrollTo().performClick()

        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun syncNowShowsUnavailableMessageWhenNoHostConfigured() {
        compose.setContent { SettingsScreen(viewModel = viewModel(baseUrl = null, publicKey = null), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-sync-now-button").performScrollTo().performClick()

        val unavailable = compose.activity.getString(R.string.settings_sync_unavailable_message)
        compose.onNodeWithText(unavailable).assertExists()
    }

    @Test
    fun syncNowUnavailableMessageIsDismissible() {
        compose.setContent { SettingsScreen(viewModel = viewModel(baseUrl = null, publicKey = null), onSettingsChanged = {}) }

        compose.onNodeWithTag("settings-sync-now-button").performScrollTo().performClick()
        compose.onNodeWithTag("settings-sync-message-dismiss").performScrollTo().performClick()

        val unavailable = compose.activity.getString(R.string.settings_sync_unavailable_message)
        compose.onNodeWithText(unavailable).assertDoesNotExist()
    }

    @Test
    fun dismissControlHasAVisibleLabelNotJustAnIconOrContentDescription() {
        // Regression coverage for review finding: every other icon-bearing control in this app (hazard tiles,
        // the read-aloud button, the hazard chip) pairs its icon with visible Text, not a screen-reader-only
        // contentDescription alone.
        compose.setContent { SettingsScreen(viewModel = viewModel(baseUrl = null, publicKey = null), onSettingsChanged = {}) }
        compose.onNodeWithTag("settings-sync-now-button").performScrollTo().performClick()

        val dismissLabel = compose.activity.getString(R.string.settings_sync_dismiss_label)
        compose.onNodeWithText(dismissLabel).assertExists()
    }

    @Test
    fun lastSyncShowsNeverWhenNoSyncHasEverRun() {
        compose.setContent { SettingsScreen(viewModel = viewModel(), onSettingsChanged = {}) }

        val never = compose.activity.getString(R.string.settings_last_sync_never)
        compose.onNodeWithText(never, substring = true).assertExists()
    }
}
