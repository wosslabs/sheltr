import { parseBundleManifest, parseDocumentFile } from '../core/bundle/parser';
import type { ContentStore } from '../core/store/contentStore';
import { SAMPLE_DOCUMENTS, SAMPLE_MANIFEST } from './sampleBundleAssets';

/**
 * Installs the sample content that ships inside the app, but only on first run: does nothing once any content is
 * installed. The sample is part of the signed app binary, so unlike a downloaded bundle it is not
 * signature-checked; the repository test (sampleBundle.test.ts) checks its files against their checksums.
 */
export async function installSampleBundleIfEmpty(store: ContentStore): Promise<void> {
  if ((await store.bundleVersion()) !== null) return;
  const manifest = parseBundleManifest(JSON.stringify(SAMPLE_MANIFEST));
  await store.install(manifest, (document) => {
    const raw = SAMPLE_DOCUMENTS[document.id];
    if (raw === undefined) throw new Error(`sample bundle has no file for document ${document.id}`);
    return parseDocumentFile(JSON.stringify(raw));
  });
}
