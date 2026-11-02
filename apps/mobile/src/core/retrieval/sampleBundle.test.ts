import * as fs from 'node:fs';
import * as path from 'node:path';
import { BUNDLE_FORMAT_VERSION } from '../bundle/format';
import { parseBundleManifest, parseDocumentFileBytes, verifyDocumentFile } from '../bundle/parser';
import { utf8Decode } from '../bytes';
import { ContentStore } from '../store/contentStore';
import { NodeSqliteDatabase } from '../testing/nodeSqlite';
import { KEYWORD_RELEVANCE_THRESHOLD, Retriever } from './retriever';

/** The sample bundle shipped in the app (assets/sample-bundle) is real publisher output; check it end to end. */
const dir = path.join(__dirname, '..', '..', '..', 'assets', 'sample-bundle');
const manifest = parseBundleManifest(fs.readFileSync(path.join(dir, 'bundle.json'), 'utf8'));

describe('the shipped sample bundle', () => {
  it('uses format version 1 and has the expected documents', () => {
    expect(manifest.formatVersion).toBe(BUNDLE_FORMAT_VERSION);
    expect(manifest.documents.map((d) => d.title).sort()).toEqual([
      'Prepare',
      'Public awareness and public education for disaster risk reduction: key messages (Short version)',
    ]);
  });

  it('has document files whose bytes match the checksums in the manifest', () => {
    for (const document of manifest.documents) {
      const bytes = new Uint8Array(fs.readFileSync(path.join(dir, 'documents', `${document.id}.json`)));
      expect(() => verifyDocumentFile(document, bytes)).not.toThrow();
      expect(parseDocumentFileBytes(bytes).chunks).toHaveLength(document.chunks.length);
    }
  });

  it('installs and answers a question', async () => {
    const store = await ContentStore.open(new NodeSqliteDatabase());
    await store.install(manifest, (document) =>
      parseDocumentFileBytes(new Uint8Array(fs.readFileSync(path.join(dir, 'documents', `${document.id}.json`)))),
    );
    expect(await store.bundleVersion()).toBe(manifest.bundleVersion);
    const result = await new Retriever(store).retrieve({ text: 'what should I do during an earthquake' });
    expect(result.kind === 'passages' && result.passages[0].score).toBeGreaterThanOrEqual(KEYWORD_RELEVANCE_THRESHOLD);
    expect(utf8Decode(new Uint8Array([104, 105]))).toBe('hi');
  });
});
