package com.sheltr.shared.store

import com.sheltr.shared.bundle.BundleJson
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentFile
import kotlinx.serialization.decodeFromString

/**
 * Reads the bundled sample content shipped as a repository asset (apps/mobile/android-sample-bundle on disk;
 * an Android app reads it from its assets, a future task's concern). Two methods, not one: unlike
 * [DocumentSource], there is no single call this reduces to, so this is a plain interface, not a fun interface.
 */
interface BundleAssetReader {
    /** The exact text of bundle.json. */
    fun readManifestJson(): String

    /** The exact text of documents/<documentId>.json. */
    fun readDocumentJson(documentId: String): String
}

/**
 * Installs the sample content bundle read through [assets] as the store's initial content, but only on first run:
 * no-ops once anything is installed ([ContentStore.bundleVersion] is non-null). This bundle is not signed and is
 * not verified here -- it ships inside the app as a repository asset, unlike [com.sheltr.shared.sync.SyncEngine]'s
 * network-fetched bundles, which are verified against the publisher's signature before anything is installed.
 *
 * A missing or malformed document asset fails [BundleJson.decodeFromString] or [ContentStore.install] itself; the
 * exception propagates and, per [ContentStore.install]'s all-or-nothing staging/swap contract, the store is left
 * exactly as it was (empty, on first run) rather than partially installed.
 */
fun installSampleBundleIfEmpty(store: ContentStore, assets: BundleAssetReader) {
    if (store.bundleVersion() != null) return

    val manifest = BundleJson.decodeFromString<BundleManifest>(assets.readManifestJson())
    val documents = DocumentSource { document ->
        BundleJson.decodeFromString<DocumentFile>(assets.readDocumentJson(document.id))
    }
    store.install(manifest, documents)
}
