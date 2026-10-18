package com.sheltr.android

import android.app.Application
import android.content.res.AssetManager
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
     * Completes once the first-run sample-bundle install (or its no-op skip, see [installSampleBundleIfEmpty])
     * finishes. [ContentStore] is "not thread safe: the caller serialises install with reads" (its own doc
     * comment), so a screen's ViewModel reading [contentStore] or [retriever] for the first time must await this
     * rather than racing the install. No screen in this task's stub routes reads either, so nothing awaits it yet;
     * the first screen that does (Home, a later task in this plan) is responsible for awaiting it.
     */
    lateinit var sampleBundleReady: Deferred<Unit>
        private set

    /**
     * Built lazily, not eagerly in [onCreate]: constructing `HttpClient()` resolves an HTTP engine from the
     * classpath immediately, and apps/mobile/shared's androidMain deliberately ships no such engine (see that
     * module's build.gradle.kts comment on `ktor-client-okhttp`) -- an eager `SyncEngine(HttpClient())` would
     * crash every launch with no engine found. Nothing calls into this before a future hosting plan configures
     * [publisherBaseUrl], so deferring construction until first use is safe.
     */
    val syncEngine: SyncEngine by lazy { SyncEngine(HttpClient()) }

    /** Null until a bundle host exists; see [syncEngine]'s doc comment. Task 5's SettingsViewModel consumes this. */
    val publisherBaseUrl: String?
        get() = BuildConfig.PUBLISHER_BASE_URL.ifEmpty { null }

    /** Null until a bundle host exists; see [syncEngine]'s doc comment. Task 5's SettingsViewModel consumes this. */
    val publisherPublicKey: ByteArray?
        get() = BuildConfig.PUBLISHER_PUBLIC_KEY_HEX.ifEmpty { null }?.let(::decodeHex)

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val storeDirectory = File(filesDir, "content").apply { mkdirs() }
        contentStore = ContentStore(storeDirectory.absolutePath, AndroidStoreDriverFactory(this))
        settingsStore = AndroidSettingsStore(this)
        retriever = Retriever(contentStore)

        // Off the main thread: installSampleBundleIfEmpty may parse, checksum-verify and write several document
        // files through ContentStore.install, none of which belongs on the UI thread.
        sampleBundleReady = applicationScope.async {
            installSampleBundleIfEmpty(contentStore, AndroidAssetBundleReader(assets))
        }
    }
}

/** Decodes [hex] (lower- or upper-case, no separators) into the bytes it represents. */
private fun decodeHex(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i ->
        ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }

/**
 * [BundleAssetReader] over the sample bundle shipped at src/main/assets/sample-bundle, copied from the
 * repository's apps/mobile/android-sample-bundle (see [installSampleBundleIfEmpty]'s doc comment).
 */
private class AndroidAssetBundleReader(private val assets: AssetManager) : BundleAssetReader {
    override fun readManifestJson(): String =
        assets.open("$BUNDLE_ASSET_DIR/bundle.json").use { it.readBytes().decodeToString() }

    override fun readDocumentJson(documentId: String): ByteArray =
        assets.open("$BUNDLE_ASSET_DIR/documents/$documentId.json").use { it.readBytes() }

    private companion object {
        const val BUNDLE_ASSET_DIR = "sample-bundle"
    }
}
