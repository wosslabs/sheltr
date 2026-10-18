package com.sheltr.android.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.sheltr.android.R
import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.TextScale
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The language picker's four fixed options (spec requirement, not derived from any locale list). [tag] is the
 * BCP-47 tag passed to [AppCompatDelegate.setApplicationLocales] (`""` selects the system default); [labelRes]
 * is null for the three language names, which are deliberately literal/untranslated (see [languageDisplayName]).
 */
private data class LanguageOption(val tag: String, val testTagSuffix: String, val labelRes: Int?)

private val languageOptions = listOf(
    LanguageOption(tag = "", testTagSuffix = "system", labelRes = R.string.settings_language_system_default),
    LanguageOption(tag = "en", testTagSuffix = "en", labelRes = null),
    LanguageOption(tag = "es", testTagSuffix = "es", labelRes = null),
    LanguageOption(tag = "fr", testTagSuffix = "fr", labelRes = null),
)

/**
 * "English"/"Español"/"Français" are hardcoded here, not `stringResource`s: each one names its own language and
 * must stay in that language regardless of the device's current UI locale (a French-reading user must still see
 * "English", not a French translation of the word). "System default" is the one option genuinely worth
 * translating -- it is UI chrome, not a language's own name -- so it alone comes from
 * [R.string.settings_language_system_default].
 */
private fun languageDisplayName(option: LanguageOption): String? = when (option.tag) {
    "en" -> "English"
    "es" -> "Español"
    "fr" -> "Français"
    else -> null
}

private val textScaleLabels: Map<TextScale, Int> = mapOf(
    TextScale.NORMAL to R.string.settings_text_size_normal,
    TextScale.LARGE to R.string.settings_text_size_large,
    TextScale.EXTRA_LARGE to R.string.settings_text_size_extra_large,
)

/**
 * Sheltr's Settings screen: country, language, text size, high contrast, and sync-now with a last-sync display.
 *
 * [viewModel] is always already constructed by [com.sheltr.android.ui.SheltrNavHost] (it needs the real
 * `ContentStore`/sync-provider/publisher config, the same reason `SearchScreen`/`AnswerScreen` take their
 * ViewModels as required parameters rather than building one with a no-arg `viewModel()` call).
 *
 * [onSettingsChanged] is [com.sheltr.android.MainActivity]'s hoisted-state setter (see that class's doc
 * comment): this screen calls it from a `LaunchedEffect` keyed on [SettingsViewModel.settings] so every write
 * (`onCountryChanged`/`onTextScaleChanged`/`onHighContrastChanged`, each of which immediately updates that
 * [kotlinx.coroutines.flow.StateFlow]) is followed by exactly one call with the new value -- including, harmlessly,
 * one call with the *unchanged* initial value the moment this screen first composes.
 *
 * The language picker below is the deliberate exception to "every setting lives in `AppSettings`": it calls
 * [AppCompatDelegate.setApplicationLocales] directly and is never read from or written to [viewModel]/
 * `SettingsStore` at all -- see [languageOptions]'s doc comment and the picker's own click handler for why.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSettingsChanged: (AppSettings) -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val lastSyncEpochSeconds by viewModel.lastSyncEpochSeconds.collectAsState()
    val syncInProgress by viewModel.syncInProgress.collectAsState()

    LaunchedEffect(settings) { onSettingsChanged(settings) }

    // Holds the raw outcome, not an already-formatted String: onSyncNowTapped's result callback runs later,
    // outside any composition, so it cannot call `stringResource` itself (see resolveSyncMessage's doc comment).
    // Formatting happens below, during composition, from this state. Plain `remember`, not `rememberSaveable`:
    // SyncOutcomeMessage isn't Parcelable, and this transient, dismissible message is not worth a custom Saver --
    // losing it across a config change/process death just means the message re-shows as absent, not wrong.
    var syncOutcome by remember { mutableStateOf<SyncOutcomeMessage?>(null) }
    val syncMessage = syncOutcome?.let { resolveSyncMessage(it) }

    Column(
        modifier = Modifier
            .testTag("settings-screen")
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.heightIn(min = 16.dp))

        CountrySection(selectedCountry = settings.country, onCountryChanged = viewModel::onCountryChanged)
        Spacer(Modifier.heightIn(min = 24.dp))

        LanguageSection()
        Spacer(Modifier.heightIn(min = 24.dp))

        TextSizeSection(current = settings.textScale, onTextScaleChanged = viewModel::onTextScaleChanged)
        Spacer(Modifier.heightIn(min = 24.dp))

        HighContrastSection(enabled = settings.highContrast, onHighContrastChanged = viewModel::onHighContrastChanged)
        Spacer(Modifier.heightIn(min = 24.dp))

        SyncSection(
            lastSyncEpochSeconds = lastSyncEpochSeconds,
            syncInProgress = syncInProgress,
            message = syncMessage,
            onDismissMessage = { syncOutcome = null },
            onSyncNowTapped = { viewModel.onSyncNowTapped { outcome -> syncOutcome = outcome } },
        )
    }
}

/**
 * Formats [outcome] through this screen's own string resources -- each a template with `%1$s`/`%2$d` placeholders
 * for the raw data [SyncOutcomeMessage] carries (bundle version, document count, failure reason), the same
 * pattern `settings_last_sync_label`/`answer_source_published` already use elsewhere in this app. `@Composable`,
 * called from [SettingsScreen]'s body during composition -- never from [SettingsViewModel.onSyncNowTapped]'s
 * result callback itself, which runs later, outside any composition, and so cannot call `stringResource`.
 */
@Composable
private fun resolveSyncMessage(outcome: SyncOutcomeMessage): String = when (outcome) {
    SyncOutcomeMessage.Unavailable -> stringResource(R.string.settings_sync_unavailable_message)
    is SyncOutcomeMessage.UpToDate -> stringResource(R.string.settings_sync_up_to_date_message, outcome.bundleVersion)
    is SyncOutcomeMessage.Updated -> stringResource(
        R.string.settings_sync_updated_message,
        outcome.bundleVersion,
        outcome.documentsDownloaded,
    )
    SyncOutcomeMessage.RejectedSignature -> stringResource(R.string.settings_sync_rejected_signature_message)
    is SyncOutcomeMessage.Failed -> stringResource(R.string.settings_sync_failed_message, outcome.reason)
}

@Composable
private fun CountrySection(selectedCountry: String?, onCountryChanged: (String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val currentLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val options = remember(currentLocale) {
        Locale.getISOCountries()
            .map { code -> code to Locale("", code).getDisplayCountry(currentLocale) }
            .sortedBy { it.second }
    }
    val notSetLabel = stringResource(R.string.settings_country_not_set)
    val currentLabel = selectedCountry?.let { Locale("", it).getDisplayCountry(currentLocale) } ?: notSetLabel

    Text(stringResource(R.string.settings_country_label), style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { expanded = !expanded }
            .testTag("settings-country-selector")
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(currentLabel, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        // "▾" rather than an Icon: ExpandMore/ArrowDropDown aren't in material-icons-core (only the handful of
        // icons already used elsewhere in this app -- Close/Search/PlayArrow -- are), and pulling in
        // material-icons-extended for one decorative glyph is not worth the dependency.
        Text("▾")
    }

    if (expanded) {
        // Not its own `heightIn(max=...).verticalScroll(...)`: nesting a second scrollable inside the screen's
        // single outer `verticalScroll` Column (this composable's caller) makes some descendants unreachable by
        // `performScrollTo()` in tests, and gains little in a screen that already scrolls as a whole -- a plain
        // Column lets the outer scroll carry this list too, same as everything else on this screen.
        Column {
            options.forEach { (code, label) ->
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            onCountryChanged(code)
                            expanded = false
                        }
                        .testTag("settings-country-option-$code")
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LanguageSection() {
    // AppCompatDelegate's application-locale override is Android/AppCompat's own per-app-language mechanism: it
    // persists itself (no SettingsStore write needed) and survives process death on its own. Deliberately NOT
    // routed through SettingsStore/AppSettings -- the only setting on this screen that isn't -- because it is a
    // presentation-only concern (which language menus/labels render in) with no effect on retrieval, boosting,
    // or any other domain behavior those settings drive.
    //
    // `remember`, seeded from AppCompatDelegate's current value, then updated directly in the click handler
    // below: AppCompatDelegate itself is not Compose state, so nothing would otherwise tell this composable to
    // recompose when it changes. In production a locale change also recreates the Activity (AppCompat's own
    // pre-33 behavior), which would pick up the new value on its own -- but that recreation is not guaranteed to
    // happen inside the same frame as the tap, so this local mirror is what gives the radio selection its
    // immediate visual feedback either way.
    var selectedTag by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags()) }

    Text(stringResource(R.string.settings_language_label), style = MaterialTheme.typography.titleMedium)
    Column {
        languageOptions.forEach { option ->
            val label = languageDisplayName(option) ?: stringResource(option.labelRes!!)
            val selected = selectedTag == option.tag || (option.tag == "" && selectedTag.isEmpty())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    // `selectable`, not `clickable`: this row's RadioButton gives it Role.RadioButton semantics
                    // (announced by TalkBack as "radio button... selected/not selected"), which a plain clickable
                    // row would not convey.
                    .selectable(
                        selected = selected,
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(option.tag))
                            selectedTag = option.tag
                        },
                    )
                    .testTag("settings-language-option-${option.testTagSuffix}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }
    Spacer(Modifier.heightIn(min = 8.dp))
    Text(
        text = stringResource(R.string.settings_language_notice),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun TextSizeSection(current: TextScale, onTextScaleChanged: (TextScale) -> Unit) {
    Text(stringResource(R.string.settings_text_size_label), style = MaterialTheme.typography.titleMedium)
    Row(modifier = Modifier.fillMaxWidth()) {
        TextScale.entries.forEach { scale ->
            val label = stringResource(textScaleLabels.getValue(scale))
            val selected = scale == current
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onTextScaleChanged(scale) }
                    .testTag("settings-text-size-${scale.name}")
                    .padding(end = 24.dp),
            )
        }
    }
}

@Composable
private fun HighContrastSection(enabled: Boolean, onHighContrastChanged: (Boolean) -> Unit) {
    val label = stringResource(R.string.settings_high_contrast_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = onHighContrastChanged,
            modifier = Modifier.testTag("settings-high-contrast-switch"),
        )
    }
}

@Composable
private fun SyncSection(
    lastSyncEpochSeconds: Long?,
    syncInProgress: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    onSyncNowTapped: () -> Unit,
) {
    val neverLabel = stringResource(R.string.settings_last_sync_never)
    val lastSyncText = lastSyncEpochSeconds?.let { formatEpochSeconds(it) } ?: neverLabel
    Text(stringResource(R.string.settings_last_sync_label, lastSyncText))
    Spacer(Modifier.heightIn(min = 8.dp))

    Button(
        onClick = onSyncNowTapped,
        enabled = !syncInProgress,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("settings-sync-now-button"),
    ) {
        if (syncInProgress) {
            CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.settings_sync_now_label))
        }
    }

    message?.let { text ->
        Spacer(Modifier.heightIn(min = 8.dp))
        val dismissLabel = stringResource(R.string.settings_sync_dismiss_label)
        Row(
            modifier = Modifier.fillMaxWidth().testTag("settings-sync-message"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onDismissMessage() }
                    .testTag("settings-sync-message-dismiss")
                    // Decorative: the visible Text below already carries the accessible label (same pattern as
                    // AnswerScreen's read-aloud button) -- a visible label alongside the icon, not an icon-only
                    // control, matching every other icon-bearing control in this app (hazard tiles, read-aloud,
                    // the hazard chip).
                    .semantics { contentDescription = dismissLabel }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(dismissLabel)
            }
        }
    }
}

private fun formatEpochSeconds(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(epochSeconds * 1000))
