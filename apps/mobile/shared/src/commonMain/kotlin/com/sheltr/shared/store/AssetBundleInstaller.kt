package com.sheltr.shared.store

import com.sheltr.shared.bundle.parseBundleManifest
import com.sheltr.shared.bundle.parseDocumentFileBytes
import com.sheltr.shared.bundle.verifyDocumentFile

/**
 * Reads the bundled sample content shipped as a repository asset (apps/mobile/android-sample-bundle on disk;
 * an Android app reads it from its assets, a future task's concern). Two methods, not one: unlike
 * [DocumentSource], there is no single call this reduces to, so this is a plain interface, not a fun interface.
 */
interface BundleAssetReader {
    /** The exact text of bundle.json. */
    fun readManifestJson(): String

    /** The exact bytes of documents/<documentId>.json, as stored -- needed to verify its fileChecksum. */
    fun readDocumentJson(documentId: String): ByteArray
}

/**
 * Installs the sample content bundle read through [assets] as the store's initial content, but only on first run:
 * no-ops once anything is installed ([ContentStore.bundleVersion] is non-null). This bundle is not signed and is
 * not verified here -- it ships inside the app as a repository asset, unlike [com.sheltr.shared.sync.SyncEngine]'s
 * network-fetched bundles, which are verified against the publisher's signature before anything is installed.
 *
 * Each document file is still checked against its manifest-declared fileChecksum, through the same
 * [verifyDocumentFile]/[parseDocumentFileBytes] path [com.sheltr.shared.sync.SyncEngine] uses: later syncs trust
 * a stored document's fileChecksum to decide whether its chunks can be reused without re-downloading, so a
 * sample document that drifted from its own checksum must fail here rather than silently seed that invariant
 * with unverified content.
 *
 * A missing or malformed manifest, a checksum mismatch, or a malformed document file fails [parseBundleManifest],
 * [verifyDocumentFile] or [ContentStore.install] itself; the exception propagates and, per [ContentStore.install]'s
 * all-or-nothing staging/swap contract, the store is left exactly as it was (empty, on first run) rather than
 * partially installed.
 */
fun installSampleBundleIfEmpty(store: ContentStore, assets: BundleAssetReader) {
    if (store.bundleVersion() != null) return

    val manifest = parseBundleManifest(assets.readManifestJson())
    val documents = DocumentSource { document ->
        val bytes = assets.readDocumentJson(document.id)
        verifyDocumentFile(document, bytes)
        parseDocumentFileBytes(bytes)
    }
    store.install(manifest, documents)
}
