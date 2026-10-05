package com.sheltr.shared.sync

import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentChunk
import com.sheltr.shared.bundle.DocumentFile
import com.sheltr.shared.bundle.parseBundleManifest
import com.sheltr.shared.bundle.parseDocumentFileBytes
import com.sheltr.shared.bundle.verifyDocumentFile
import com.sheltr.shared.signing.verifySignature
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.DocumentSource
import com.sheltr.shared.store.StoreReader
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** The outcome of one [SyncEngine.run]. Only [Updated] changes the content. */
sealed interface SyncResult {
    /** The published bundle is the one installed. Nothing was downloaded beyond the manifest and signature. */
    data class UpToDate(val bundleVersion: String) : SyncResult

    /** [bundleVersion] is now active; [documentsDownloaded] document files were fetched, the rest reused. */
    data class Updated(val bundleVersion: String, val documentsDownloaded: Int) : SyncResult

    /** bundle.json did not verify against the public key. The content was not touched. */
    data object RejectedSignature : SyncResult

    /** Network, format, checksum or store error. The previous content stays active and unchanged. */
    data class Failed(val reason: String) : SyncResult
}

/**
 * One sync of the on-device content with a published bundle (spec §4):
 *
 *  1. fetch bundle.json and bundle.json.sig;
 *  2. verify the Ed25519 signature over the exact manifest bytes; a bad signature ends the sync before anything
 *     else is read from the manifest or the store is touched;
 *  3. parse the manifest (formatVersion 1 only); the installed bundleVersion means UpToDate;
 *  4. download only the documents whose fileChecksum differs from the stored one (or that are new), and check each
 *     download's SHA-256 against the signed manifest; documents the manifest no longer lists are dropped by the
 *     install, which builds the new content from the manifest alone;
 *  5. install through [ContentStore.install], which swaps the content atomically or leaves it unchanged.
 *
 * Every download finishes and is verified before the store is changed, so a failure at any step (including an
 * interrupted download) returns [SyncResult.Failed] with the old content active. Downloaded files are held in
 * memory until the install.
 *
 * The engine does not schedule, wait or retry; the caller does that with [RetryPolicy] and the settings of M7.
 * The last sync time is stored only on [SyncResult.Updated] and [SyncResult.UpToDate].
 */
class SyncEngine(
    private val clientFor: (baseUrl: String) -> SyncClient,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    /** Production engine: Ktor with the target's default engine. The caller owns and closes [http]. */
    constructor(http: HttpClient) : this({ baseUrl -> KtorSyncClient(http, baseUrl) })

    suspend fun run(baseUrl: String, publicKey: ByteArray, store: ContentStore): SyncResult {
        return try {
            sync(clientFor(baseUrl), publicKey, store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e::class.simpleName ?: "sync failed")
        }
    }

    private suspend fun sync(client: SyncClient, publicKey: ByteArray, store: ContentStore): SyncResult {
        val manifestBytes = client.fetchManifestBytes()
        val signature = client.fetchSignatureBytes()
        if (!verifySignature(publicKey, manifestBytes, signature)) {
            return SyncResult.RejectedSignature
        }

        val manifest = parseBundleManifest(manifestBytes.decodeToString())
        if (manifest.bundleVersion == store.bundleVersion()) {
            store.recordSync(nowEpochSeconds())
            return SyncResult.UpToDate(manifest.bundleVersion)
        }

        val downloaded = downloadChanged(client, manifest, store)
        store.install(manifest, DiffDocumentSource(downloaded, store))
        store.recordSync(nowEpochSeconds())
        return SyncResult.Updated(manifest.bundleVersion, downloaded.size)
    }

    /** Fetches and verifies every document whose file differs from the stored one. Throws on the first failure. */
    private suspend fun downloadChanged(
        client: SyncClient,
        manifest: BundleManifest,
        store: StoreReader,
    ): Map<String, DocumentFile> {
        val downloaded = LinkedHashMap<String, DocumentFile>()
        for (document in manifest.documents) {
            if (store.document(document.id)?.fileChecksum == document.fileChecksum) continue
            val bytes = client.fetchDocumentFile(document.id)
            verifyDocumentFile(document, bytes)
            downloaded[document.id] = parseDocumentFileBytes(bytes)
        }
        return downloaded
    }
}

/**
 * Downloaded files for changed documents; the stored chunks for unchanged ones. An unchanged document has the same
 * fileChecksum as the stored one, so its stored chunks are the content of that exact file.
 */
private class DiffDocumentSource(
    private val downloaded: Map<String, DocumentFile>,
    private val store: StoreReader,
) : DocumentSource {
    override fun load(document: BundleDocument): DocumentFile {
        downloaded[document.id]?.let { return it }
        val chunks = store.chunks(document.id).map { DocumentChunk(it.id, it.content, it.pageNumber) }
        return DocumentFile(document.id, chunks)
    }
}
