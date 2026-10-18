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
     * [onResult] is called exactly once: immediately with [SYNC_UNAVAILABLE_MESSAGE_KEY] -- a lookup key, not a
     * localized string, so the screen can resolve it through `stringResource` -- when [baseUrl]/[publicKey] are
     * not both configured (without ever calling [syncEngine]); otherwise once the sync (run on [ioDispatcher])
     * completes, with a plain description of the resulting [SyncResult].
     */
    fun onSyncNowTapped(onResult: (String) -> Unit) {
        val url = baseUrl
        val key = publicKey
        if (url.isNullOrBlank() || key == null) {
            onResult(SYNC_UNAVAILABLE_MESSAGE_KEY)
            return
        }
        _syncInProgress.value = true
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { syncEngine().run(url, key, store) }
            _syncInProgress.value = false
            _lastSyncEpochSeconds.value = store.lastSyncEpochSeconds()
            onResult(describe(result))
        }
    }

    /**
     * A plain, English description of [result] -- not a localized string resource. Only [SYNC_UNAVAILABLE_MESSAGE_KEY]
     * (the one new string this task's brief lists for sync outcomes) is a genuine, pre-sync UX message; the
     * outcome of a sync that actually ran is closer to diagnostic/technical detail (bundle versions, document
     * counts, raw failure reasons already untranslated at the source -- see [SyncResult.Failed.reason]) than to
     * core UX copy, so it is not worth four more string resources (YAGNI) for a transient, dismissible message.
     */
    private fun describe(result: SyncResult): String = when (result) {
        is SyncResult.UpToDate -> "Up to date (bundle ${result.bundleVersion})"
        is SyncResult.Updated ->
            "Updated to bundle ${result.bundleVersion} (${result.documentsDownloaded} documents downloaded)"
        SyncResult.RejectedSignature -> "Sync rejected: the published bundle's signature did not verify"
        is SyncResult.Failed -> "Sync failed: ${result.reason}"
    }

    companion object {
        /** Lookup key, not a localized string; the screen resolves it to `R.string.settings_sync_unavailable_message`. */
        const val SYNC_UNAVAILABLE_MESSAGE_KEY = "settings_sync_unavailable_message"
    }
}
