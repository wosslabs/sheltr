import type { BundleDocument, BundleManifest, DocumentFile } from '../bundle/format';
import { parseBundleManifest, parseDocumentFileBytes, verifyDocumentFile } from '../bundle/parser';
import { utf8Decode } from '../bytes';
import { verifySignature } from '../signing';
import type { ContentStore } from '../store/contentStore';
import type { StoreReader } from '../store/types';
import { FetchSyncClient, type SyncClient } from './client';
import type { SyncResult } from './result';

/**
 * One sync of the on-device content with a published bundle:
 *
 *  1. fetch bundle.json and bundle.json.sig;
 *  2. verify the Ed25519 signature over the exact manifest bytes; a bad signature ends the sync before anything
 *     else is read from the manifest or the store is touched;
 *  3. parse the manifest (formatVersion 1 only); the installed bundleVersion means upToDate;
 *  4. download only the documents whose fileChecksum differs from the stored one (or that are new), and check each
 *     download's SHA-256 against the signed manifest; documents the manifest no longer lists are dropped by the
 *     install, which builds the new content from the manifest alone;
 *  5. install through ContentStore.install, which replaces the content atomically or leaves it unchanged.
 *
 * Every download finishes and is verified before the store is changed, so a failure at any step (including an
 * interrupted download) returns 'failed' with the old content active. Downloaded files are held in memory until
 * the install. The engine does not schedule, wait or retry; the caller does that with RetryPolicy.
 */
export class SyncEngine {
  constructor(
    private readonly clientFor: (baseUrl: string) => SyncClient = (baseUrl) => new FetchSyncClient(baseUrl),
    private readonly nowEpochSeconds: () => number = () => Math.floor(Date.now() / 1000),
  ) {}

  async run(baseUrl: string, publicKey: Uint8Array, store: ContentStore): Promise<SyncResult> {
    try {
      return await this.sync(this.clientFor(baseUrl), publicKey, store);
    } catch (e) {
      return { kind: 'failed', reason: e instanceof Error && e.message ? e.message : 'sync failed' };
    }
  }

  private async sync(client: SyncClient, publicKey: Uint8Array, store: ContentStore): Promise<SyncResult> {
    const manifestBytes = await client.fetchManifestBytes();
    const signature = await client.fetchSignatureBytes();
    if (!verifySignature(publicKey, manifestBytes, signature)) return { kind: 'rejectedSignature' };

    const manifest = parseBundleManifest(utf8Decode(manifestBytes));
    if (manifest.bundleVersion === (await store.bundleVersion())) {
      await store.recordSync(this.nowEpochSeconds());
      return { kind: 'upToDate', bundleVersion: manifest.bundleVersion };
    }

    const downloaded = await downloadChanged(client, manifest, store);
    await store.install(manifest, (document) => documentFor(document, downloaded, store));
    await store.recordSync(this.nowEpochSeconds());
    return { kind: 'updated', bundleVersion: manifest.bundleVersion, documentsDownloaded: downloaded.size };
  }
}

/** Fetches and verifies every document whose file differs from the stored one. Throws on the first failure. */
async function downloadChanged(
  client: SyncClient,
  manifest: BundleManifest,
  store: StoreReader,
): Promise<Map<string, DocumentFile>> {
  const downloaded = new Map<string, DocumentFile>();
  for (const document of manifest.documents) {
    if ((await store.document(document.id))?.fileChecksum === document.fileChecksum) continue;
    const bytes = await client.fetchDocumentFile(document.id);
    verifyDocumentFile(document, bytes);
    downloaded.set(document.id, parseDocumentFileBytes(bytes));
  }
  return downloaded;
}

/**
 * Downloaded files for changed documents; the stored chunks for unchanged ones. An unchanged document has the same
 * fileChecksum as the stored one, so its stored chunks are the content of that exact file.
 */
async function documentFor(
  document: BundleDocument,
  downloaded: ReadonlyMap<string, DocumentFile>,
  store: StoreReader,
): Promise<DocumentFile> {
  const fresh = downloaded.get(document.id);
  if (fresh !== undefined) return fresh;
  const chunks = await store.chunks(document.id);
  return {
    id: document.id,
    chunks: chunks.map((c) => ({ id: c.id, content: c.content, pageNumber: c.pageNumber })),
  };
}
