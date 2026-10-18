package com.sheltr.android.ui.settings

import com.sheltr.shared.settings.AppSettings
import com.sheltr.shared.settings.InMemorySettingsStore
import com.sheltr.shared.settings.TextScale
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.StoreDriverFactory
import com.sheltr.shared.sync.SyncClient
import com.sheltr.shared.sync.SyncEngine
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Same reasoning as `SearchViewModelTest.MainDispatcherRule`: `viewModelScope` resolves `Dispatchers.Main` at
 * launch time, and there is no Main dispatcher under plain JUnit unless one is installed. Duplicated here (not
 * shared) because no test-utility module exists yet for this app module -- same convention `SearchViewModelTest`
 * and `AnswerViewModelTest` already each established independently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) :
    TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * A fake [SyncClient] that never touches the network: [fetchManifestBytes] either returns [manifestBytes] or
 * throws [failure] when set, so tests can drive [SyncEngine.run] to a deterministic [com.sheltr.shared.sync.SyncResult]
 * without Ktor or a real publisher.
 */
private class FakeSyncClient(private val failure: Throwable) : SyncClient {
    override suspend fun fetchManifestBytes(): ByteArray = throw failure
    override suspend fun fetchSignatureBytes(): ByteArray = throw failure
    override suspend fun fetchDocumentFile(id: String): ByteArray = throw failure
}

/**
 * A fresh, never-opened [ContentStore]: no content.db file exists in [directory], so `ContentStore.openActive`
 * returns before ever calling the driver factory (see that class's `init`/`openActive`). Lets tests exercise
 * [SettingsViewModel] (both the display-only `lastSyncEpochSeconds()` path and, via a real [SyncEngine] wired to
 * [FakeSyncClient], the actual sync path) without a real SQLite driver -- the same technique
 * `SheltrNavHostTest` already uses for its stub-screen smoke test.
 */
private fun freshContentStore(): ContentStore {
    val dir = Files.createTempDirectory("settings-viewmodel-test").toFile()
    return ContentStore(
        dir.absolutePath,
        StoreDriverFactory { _, _ -> error("not expected to be called: no content.db exists in this fresh directory") },
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    internal val mainDispatcherRule = MainDispatcherRule()

    private val stores = mutableListOf<ContentStore>()

    private fun newStore(): ContentStore = freshContentStore().also { stores += it }

    @After
    fun closeStores() {
        stores.forEach { it.close() }
    }

    @Test
    fun changingCountryPersistsThroughTheStore() {
        val store = InMemorySettingsStore()
        val vm = SettingsViewModel(
            settingsStore = store,
            syncEngine = { error("not expected: sync never requested in this test") },
            store = newStore(),
            baseUrl = null,
            publicKey = null,
        )

        vm.onCountryChanged("KE")

        assertEquals("KE", store.get().country)
        assertEquals("KE", vm.settings.value.country)
    }

    @Test
    fun changingTextScalePersistsThroughTheStore() {
        val store = InMemorySettingsStore()
        val vm = SettingsViewModel(
            settingsStore = store,
            syncEngine = { error("not expected: sync never requested in this test") },
            store = newStore(),
            baseUrl = null,
            publicKey = null,
        )

        vm.onTextScaleChanged(TextScale.LARGE)

        assertEquals(TextScale.LARGE, store.get().textScale)
        assertEquals(TextScale.LARGE, vm.settings.value.textScale)
    }

    @Test
    fun changingHighContrastPersistsThroughTheStore() {
        val store = InMemorySettingsStore()
        val vm = SettingsViewModel(
            settingsStore = store,
            syncEngine = { error("not expected: sync never requested in this test") },
            store = newStore(),
            baseUrl = null,
            publicKey = null,
        )

        vm.onHighContrastChanged(true)

        assertTrue(store.get().highContrast)
        assertTrue(vm.settings.value.highContrast)
    }

    @Test
    fun eachWriteOnlyChangesItsOwnFieldNotTheRestOfSettings() {
        val initial = AppSettings(country = "US", textScale = TextScale.LARGE, highContrast = true)
        val store = InMemorySettingsStore(initial)
        val vm = SettingsViewModel(
            settingsStore = store,
            syncEngine = { error("not expected: sync never requested in this test") },
            store = newStore(),
            baseUrl = null,
            publicKey = null,
        )

        vm.onCountryChanged("KE")

        assertEquals("KE", vm.settings.value.country)
        assertEquals(TextScale.LARGE, vm.settings.value.textScale)
        assertTrue(vm.settings.value.highContrast)
    }

    @Test
    fun syncNowReportsUnavailableWhenNoBaseUrlConfigured() {
        val fakeSyncEngine = { error("must not be called: no base URL is configured") as SyncEngine }
        val vm = SettingsViewModel(
            settingsStore = InMemorySettingsStore(),
            syncEngine = fakeSyncEngine,
            store = newStore(),
            baseUrl = null,
            publicKey = byteArrayOf(1, 2, 3),
        )

        var message: SyncOutcomeMessage? = null
        vm.onSyncNowTapped { message = it }

        assertEquals(SyncOutcomeMessage.Unavailable, message)
    }

    @Test
    fun syncNowReportsUnavailableWhenBaseUrlIsBlank() {
        val vm = SettingsViewModel(
            settingsStore = InMemorySettingsStore(),
            syncEngine = { error("must not be called: base URL is blank") },
            store = newStore(),
            baseUrl = "   ",
            publicKey = byteArrayOf(1, 2, 3),
        )

        var message: SyncOutcomeMessage? = null
        vm.onSyncNowTapped { message = it }

        assertEquals(SyncOutcomeMessage.Unavailable, message)
    }

    @Test
    fun syncNowReportsUnavailableWhenNoPublicKeyConfigured() {
        val vm = SettingsViewModel(
            settingsStore = InMemorySettingsStore(),
            syncEngine = { error("must not be called: no public key is configured") },
            store = newStore(),
            baseUrl = "https://example.org/bundle",
            publicKey = null,
        )

        var message: SyncOutcomeMessage? = null
        vm.onSyncNowTapped { message = it }

        assertEquals(SyncOutcomeMessage.Unavailable, message)
    }

    @Test
    fun syncNowNeverInvokesTheSyncEngineProviderWhenUnavailable() {
        // The crux of this task's gating requirement: the provider lambda itself must never run when no host is
        // configured. A `var invoked = false; invoked = true; SyncEngine(...)` fake proves this directly, rather
        // than relying on the `error(...)` fakes above (which only prove it via an uncaught exception).
        var providerInvoked = false
        val provider = {
            providerInvoked = true
            SyncEngine({ error("not expected") })
        }
        val vm = SettingsViewModel(
            settingsStore = InMemorySettingsStore(),
            syncEngine = provider,
            store = newStore(),
            baseUrl = null,
            publicKey = null,
        )

        vm.onSyncNowTapped {}

        assertFalse(providerInvoked)
    }

    @Test
    fun syncNowRunsOnIoDispatcherAndReportsAResultWhenHostIsConfigured() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        var capturedBaseUrl: String? = null
        val failure = RuntimeException("boom")
        val engine = SyncEngine({ baseUrl ->
            capturedBaseUrl = baseUrl
            FakeSyncClient(failure)
        })
        val publicKey = byteArrayOf(9, 8, 7)
        val vm = SettingsViewModel(
            settingsStore = InMemorySettingsStore(),
            syncEngine = { engine },
            store = newStore(),
            baseUrl = "https://example.org/bundle",
            publicKey = publicKey,
            ioDispatcher = ioDispatcher,
        )

        var message: SyncOutcomeMessage? = null
        vm.onSyncNowTapped { message = it }

        // Not yet resolved: proves the sync really dispatches onto ioDispatcher instead of running inline.
        assertNull(message)
        assertTrue(vm.syncInProgress.value)

        advanceUntilIdle()

        assertEquals("https://example.org/bundle", capturedBaseUrl)
        // The fake client throws on every call, so SyncEngine.run catches it and returns SyncResult.Failed;
        // onSyncNowTapped must translate that into the matching SyncOutcomeMessage, not swallow or misreport it.
        assertEquals(SyncOutcomeMessage.Failed("boom"), message)
        assertFalse(vm.syncInProgress.value)
    }

    @Test
    fun lastSyncEpochSecondsReflectsTheStoreAtConstructionTime() {
        val store = newStore()
        val vm = SettingsViewModel(
            settingsStore = InMemorySettingsStore(),
            syncEngine = { error("not expected: sync never requested in this test") },
            store = store,
            baseUrl = null,
            publicKey = null,
        )

        // A fresh, never-synced store (see freshContentStore's doc comment) has no recorded sync time.
        assertNull(vm.lastSyncEpochSeconds.value)
    }
}
