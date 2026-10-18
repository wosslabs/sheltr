package com.sheltr.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.SettingsStore
import com.sheltr.shared.settings.TextScale
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.sync.SyncEngine
import com.sheltr.shared.sync.SyncResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The outcome [SettingsViewModel.onSyncNowTapped] reports, for [com.sheltr.android.ui.settings.SettingsScreen] to
 * resolve into a localized, formatted message via `stringResource`. A sealed type rather than an already-formatted
 * `String`: [SettingsViewModel] has no `Context`/`stringResource` access (and shouldn't need one -- formatting
 * belongs in the UI layer), so each case carries only the raw data ([SyncResult]'s own fields) the screen's
 * format-string resources (`settings_sync_up_to_date_message`, etc.) need as `%1$s`/`%2$d` arguments.
 */
sealed interface SyncOutcomeMessage {
    /** [SettingsViewModel.baseUrl]/`publicKey` were not both configured; `syncEngine` was never called. */
    data object Unavailable : SyncOutcomeMessage

    data class UpToDate(val bundleVersion: String) : SyncOutcomeMessage

    data class Updated(val bundleVersion: String, val documentsDownloaded: Int) : SyncOutcomeMessage

    data object RejectedSignature : SyncOutcomeMessage

    data class Failed(val reason: String) : SyncOutcomeMessage
}

/**
 * Settings screen state and its four user intents: change country / text scale / high contrast (each persisted
 * through [settingsStore] and reflected in [settings]), and sync now.
 *
 * [syncEngine] is a *provider* (`() -> SyncEngine`), matching [com.sheltr.android.ui.SheltrNavHost]'s own
 * `syncEngine` parameter exactly, rather than a realized engine: [com.sheltr.android.SheltrApplication.syncEngine]
 * is built `by lazy` specifically to avoid constructing an `HttpClient` (and resolving an HTTP engine from the
 * classpath) before anything needs one. [onSyncNowTapped] checks [baseUrl]/[publicKey] *before* ever calling
 * this provider -- calling it unconditionally would build that `HttpClient` every time this ViewModel is
 * constructed (i.e. every time Settings is opened), which throws today in every build (both are null/blank; no
 * HTTP client engine is on this module's classpath -- see the `ktor-client-core` comment in
 * apps/mobile/android/build.gradle.kts).
 *
 * [store] is typed to the concrete [ContentStore], not the narrower `SyncStateStore` port this task's brief
 * sketches: [SyncEngine.run] requires a [ContentStore] (it reads the current bundle version and installs new
 * content, not just records a sync time), and [com.sheltr.android.ui.SheltrNavHost] already holds exactly one
 * such instance (`contentStore`) for every purpose -- there is no separate, lighter object to pass instead.
 * [ContentStore] itself implements `SyncStateStore`, so [lastSyncEpochSeconds] still only ever reads through
 * that narrower contract.
 *
 * [ioDispatcher] defaults to [Dispatchers.IO] for the same reason `SearchViewModel.ioDispatcher`/
 * `AnswerViewModel.ioDispatcher` do: sync is blocking network and database I/O and must not run on the main
 * thread; tests substitute a [kotlinx.coroutines.test.TestDispatcher].
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val syncEngine: () -> SyncEngine,
    private val store: ContentStore,
    private val baseUrl: String?,
    private val publicKey: ByteArray?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _settings = MutableStateFlow(settingsStore.get())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _lastSyncEpochSeconds = MutableStateFlow(store.lastSyncEpochSeconds())
    val lastSyncEpochSeconds: StateFlow<Long?> = _lastSyncEpochSeconds.asStateFlow()

    private val _syncInProgress = MutableStateFlow(false)
    val syncInProgress: StateFlow<Boolean> = _syncInProgress.asStateFlow()

    fun onCountryChanged(code: String?) = persist { it.copy(country = code) }

    fun onTextScaleChanged(scale: TextScale) = persist { it.copy(textScale = scale) }

    fun onHighContrastChanged(enabled: Boolean) = persist { it.copy(highContrast = enabled) }

    private fun persist(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        settingsStore.put(updated)
        _settings.value = updated
    }

    /**
     * [onResult] is called exactly once: immediately with [SyncOutcomeMessage.Unavailable] when [baseUrl]/
     * [publicKey] are not both configured (without ever calling [syncEngine]); otherwise once the sync (run on
     * [ioDispatcher]) completes, with the [SyncOutcomeMessage] matching the resulting [SyncResult].
     */
    fun onSyncNowTapped(onResult: (SyncOutcomeMessage) -> Unit) {
        val url = baseUrl
        val key = publicKey
        if (url.isNullOrBlank() || key == null) {
            onResult(SyncOutcomeMessage.Unavailable)
            return
        }
        _syncInProgress.value = true
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { syncEngine().run(url, key, store) }
            _syncInProgress.value = false
            _lastSyncEpochSeconds.value = store.lastSyncEpochSeconds()
            onResult(toOutcomeMessage(result))
        }
    }

    private fun toOutcomeMessage(result: SyncResult): SyncOutcomeMessage = when (result) {
        is SyncResult.UpToDate -> SyncOutcomeMessage.UpToDate(result.bundleVersion)
        is SyncResult.Updated -> SyncOutcomeMessage.Updated(result.bundleVersion, result.documentsDownloaded)
        SyncResult.RejectedSignature -> SyncOutcomeMessage.RejectedSignature
        is SyncResult.Failed -> SyncOutcomeMessage.Failed(result.reason)
    }
}
