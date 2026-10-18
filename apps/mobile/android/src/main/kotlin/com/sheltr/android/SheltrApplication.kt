package com.sheltr.android

import android.app.Application
import android.content.res.AssetManager
import android.util.Log
import com.sheltr.shared.retrieval.Retriever
import com.sheltr.shared.settings.AndroidSettingsStore
import com.sheltr.shared.settings.SettingsStore
import com.sheltr.shared.store.AndroidStoreDriverFactory
import com.sheltr.shared.store.BundleAssetReader
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.installSampleBundleIfEmpty
import com.sheltr.shared.sync.SyncEngine
import io.ktor.client.HttpClient
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Builds the app's one instance of each shared-module port -- [contentStore], [settingsStore], [retriever], and
 * (lazily) [syncEngine] -- once in [onCreate]. Every screen reads these through this class, passed down from
 * [MainActivity] into [com.sheltr.android.ui.SheltrNavHost], rather than constructing or fetching its own, so
 * there is exactly one on-device database and one settings file for the life of the process.
 */
class SheltrApplication : Application() {

    lateinit var contentStore: ContentStore
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var retriever: Retriever
        private set

    /**
     * Completes once the first-run sample-bundle install attempt (success, no-op skip, or a caught failure --
     * see [onCreate]'s try/catch) finishes. [ContentStore] is "not thread safe: the caller serialises install
     * with reads" (its own doc comment), so nothing may read [contentStore] or [retriever] before this completes.
     * [MainActivity] enforces that: it awaits this and does not compose
     * [SheltrNavHost][com.sheltr.android.ui.SheltrNavHost] (and therefore no screen) until it does, so no screen
     * needs its own awaiting logic or a reference back to this Application singleton.
     */
    lateinit var sampleBundleReady: Deferred<Unit>
        private set

    /**
     * Built lazily, not eagerly in [onCreate]: constructing `HttpClient()` resolves an HTTP engine from the
     * classpath immediately, and apps/mobile/android (like apps/mobile/shared's androidMain, see that module's
     * build.gradle.kts comment on `ktor-client-okhttp`) deliberately has no such engine dependency. **Calling
     * this property today throws**: `HttpClient()` finds no `HttpClientEngineContainer` on the classpath and
     * fails at construction, every time. Nothing in this app calls it yet -- the one planned caller, a future
     * "sync now" action, must check `publisherBaseUrl != null` first, which is always false until a bundle-hosting
     * plan exists, so that caller never reaches this in practice -- but any new caller must not assume this
     * property merely "works but does nothing": it is unusable until a future task adds an HTTP engine
     * dependency, not just unused.
     */
    val syncEngine: SyncEngine by lazy { SyncEngine(HttpClient()) }

    /**
     * Null until a bundle host exists; see [syncEngine]'s doc comment. Task 5's SettingsViewModel consumes this.
     * A `val` initializer, not a `get()`: [BuildConfig] fields are compile-time constants, so this is computed
     * once, not re-decoded on every access.
     */
    val publisherBaseUrl: String? = BuildConfig.PUBLISHER_BASE_URL.ifEmpty { null }

    /** Null until a bundle host exists; see [syncEngine]'s doc comment and [publisherBaseUrl]'s. */
    val publisherPublicKey: ByteArray? = BuildConfig.PUBLISHER_PUBLIC_KEY_HEX.ifEmpty { null }?.let(::decodeHex)

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val storeDirectory = File(filesDir, "content").apply { mkdirs() }
        contentStore = ContentStore(storeDirectory.absolutePath, AndroidStoreDriverFactory(this))
        settingsStore = AndroidSettingsStore(this)
        retriever = Retriever(contentStore)

        // Off the main thread: installSampleBundleIfEmpty may parse, checksum-verify and write several document
        // files through ContentStore.install, none of which belongs on the UI thread.
        //
        // Caught broadly (Throwable, not just Exception) and logged rather than left to propagate: this is a
        // best-effort first-run convenience, run under async's own Deferred, which silently discards an
        // exception until something calls await() -- a corrupt asset, a checksum mismatch, or (on some devices)
        // a failure loading requery's native SQLite library must never crash the app at startup or vanish
        // unlogged; ContentStore.install's own all-or-nothing contract already guarantees the store is left
        // exactly as it was (empty, on a first run) on any failure, so logging and continuing is safe.
        sampleBundleReady = applicationScope.async {
            try {
                installSampleBundleIfEmpty(contentStore, AndroidAssetBundleReader(assets))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "sample bundle install failed; the store is left empty/unchanged", e)
            }
        }
    }

    private companion object {
        const val TAG = "SheltrApplication"
    }
}

/** Decodes [hex] (lower- or upper-case, no separators) into the bytes it represents. */
private fun decodeHex(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length, got ${hex.length}" }
    return ByteArray(hex.length / 2) { i ->
        ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }
}

/**
 * [BundleAssetReader] over the sample bundle shipped as an asset source directory pointed directly at the
 * repository's apps/mobile/android-sample-bundle (see this module's build.gradle.kts `sourceSets` block, and
 * [installSampleBundleIfEmpty]'s doc comment) -- not a copy, so it can never drift from that checksum-pinned
 * source.
 */
private class AndroidAssetBundleReader(private val assets: AssetManager) : BundleAssetReader {
    override fun readManifestJson(): String =
        assets.open("bundle.json").use { it.readBytes().decodeToString() }

    override fun readDocumentJson(documentId: String): ByteArray =
        assets.open("documents/$documentId.json").use { it.readBytes() }
}
