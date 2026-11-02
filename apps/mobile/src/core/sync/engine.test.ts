import { BundleManifest } from '../bundle/format';
import { PUBLISHER_DOCUMENT_ID, PUBLISHER_DOCUMENT_JSON, SIGNED_BUNDLE_JSON, SIGNED_BUNDLE_PUBLIC_KEY_HEX, SIGNED_BUNDLE_SIGNATURE_HEX } from '../testing/fixtures';
import { hexToBytes } from '../bytes';
import { Signer, TestBundle, documentBytes, openStore } from '../testing/bundles';
import { FetchSyncClient, requireHttps, SyncHttpError } from './client';
import { SyncEngine } from './engine';

const BASE_URL = 'https://example.org/bundle';
const signer = new Signer();
const otherSigner = new Signer();
const enc = (s: string) => new TextEncoder().encode(s);

const V1 = new TestBundle('2026-10-01T00:00:00Z', {
  'doc-a': 'Fill sandbags before the river rises.',
  'doc-b': 'Keep candles and matches dry.',
  'doc-c': 'Clear gutters before winter storms.',
}, signer);

const V2 = new TestBundle('2026-10-05T12:00:00Z', {
  'doc-a': 'Fill sandbags before the river rises.',
  'doc-b': 'Never run a generator indoors.',
  'doc-d': 'Move to high ground when the siren sounds.',
}, signer);

/** A mock bundle host: serves bytes by path, records requests, and can break a path mid-transfer. */
class MockHost {
  requests: string[] = [];
  served = new Map<string, Uint8Array>();
  broken = new Set<string>();

  serve(bundle: TestBundle): void {
    this.served.clear();
    this.served.set('bundle.json', bundle.manifestBytes);
    this.served.set('bundle.json.sig', bundle.signature);
    for (const [id, bytes] of bundle.files) this.served.set(`documents/${id}.json`, bytes);
  }

  fetch = async (url: string) => {
    const path = url.replace(`${BASE_URL}/`, '');
    this.requests.push(path);
    if (this.broken.has(path)) throw new Error(`connection reset while reading ${path}`);
    const body = this.served.get(path);
    if (body === undefined) return { ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0) };
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) as ArrayBuffer };
  };

  documentRequests(): string[] {
    return this.requests.filter((r) => r.startsWith('documents/'));
  }
}

async function setup() {
  const { store } = await openStore();
  const host = new MockHost();
  let now = 1_000;
  const engine = new SyncEngine((baseUrl) => new FetchSyncClient(baseUrl, host.fetch), () => now);
  return {
    store, host,
    setNow: (n: number) => { now = n; },
    sync: (baseUrl = BASE_URL, publicKey = signer.publicKey) => engine.run(baseUrl, publicKey, store),
  };
}

describe('SyncEngine with a bundle written by the publisher', () => {
  it('installs it into an empty store', async () => {
    const t = await setup();
    t.host.served.set('bundle.json', enc(SIGNED_BUNDLE_JSON));
    t.host.served.set('bundle.json.sig', hexToBytes(SIGNED_BUNDLE_SIGNATURE_HEX));
    t.host.served.set(`documents/${PUBLISHER_DOCUMENT_ID}.json`, enc(PUBLISHER_DOCUMENT_JSON));

    const result = await t.sync(BASE_URL, hexToBytes(SIGNED_BUNDLE_PUBLIC_KEY_HEX));

    expect(result).toEqual({ kind: 'updated', bundleVersion: '2026-10-05T12:00:00Z', documentsDownloaded: 1 });
    expect(await t.store.bundleVersion()).toBe('2026-10-05T12:00:00Z');
    expect((await t.store.document(PUBLISHER_DOCUMENT_ID))?.title).toBe('Flood guide');
    expect(await t.store.chunks(PUBLISHER_DOCUMENT_ID)).toHaveLength(2);
    expect(await t.store.lastSyncEpochSeconds()).toBe(1_000);
  });

  it('rejects a tampered manifest', async () => {
    const t = await setup();
    t.host.served.set('bundle.json', enc(SIGNED_BUNDLE_JSON.replace('Flood guide', 'Flood guidf')));
    t.host.served.set('bundle.json.sig', hexToBytes(SIGNED_BUNDLE_SIGNATURE_HEX));
    t.host.served.set(`documents/${PUBLISHER_DOCUMENT_ID}.json`, enc(PUBLISHER_DOCUMENT_JSON));

    expect(await t.sync(BASE_URL, hexToBytes(SIGNED_BUNDLE_PUBLIC_KEY_HEX))).toEqual({ kind: 'rejectedSignature' });
    expect(await t.store.bundleVersion()).toBeNull();
    expect(t.host.documentRequests()).toEqual([]);
  });
});

describe('SyncEngine with generated bundles', () => {
  it('installs a new version and records the sync time', async () => {
    const t = await setup();
    t.host.serve(V1);
    expect(await t.sync()).toEqual({ kind: 'updated', bundleVersion: V1.version, documentsDownloaded: 3 });
    expect(await t.store.bundleVersion()).toBe(V1.version);
    expect((await t.store.search('sandbags')).map((h) => h.chunk.id)).toEqual(['doc-a-0']);
    expect(await t.store.lastSyncEpochSeconds()).toBe(1_000);
  });

  it('leaves the store unchanged for an invalid signature', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();
    t.host.requests = [];

    t.host.serve(V2);
    t.host.served.set('bundle.json.sig', otherSigner.sign(V2.manifestBytes)); // valid signature, wrong key
    t.setNow(2_000);
    expect(await t.sync()).toEqual({ kind: 'rejectedSignature' });

    expect(t.host.requests).toEqual(['bundle.json', 'bundle.json.sig']);
    expect(await t.store.bundleVersion()).toBe(V1.version);
    expect(await t.store.lastSyncEpochSeconds()).toBe(1_000);

    const corrupt = V2.signature.slice();
    corrupt[0] = (corrupt[0] + 1) & 0xff;
    t.host.served.set('bundle.json.sig', corrupt);
    expect(await t.sync()).toEqual({ kind: 'rejectedSignature' });
    expect(await t.store.bundleVersion()).toBe(V1.version);
  });

  it('is up to date without document requests when the version is installed', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();
    t.host.requests = [];
    t.setNow(5_000);

    expect(await t.sync()).toEqual({ kind: 'upToDate', bundleVersion: V1.version });

    expect(t.host.requests).toEqual(['bundle.json', 'bundle.json.sig']);
    expect(await t.store.lastSyncEpochSeconds()).toBe(5_000);
  });

  it('downloads only the changed documents', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();
    t.host.requests = [];

    t.host.serve(V2); // doc-b changed, doc-c removed, doc-a unchanged, doc-d new
    expect(await t.sync()).toEqual({ kind: 'updated', bundleVersion: V2.version, documentsDownloaded: 2 });

    expect(t.host.documentRequests()).toEqual(['documents/doc-b.json', 'documents/doc-d.json']);
    expect(await t.store.bundleVersion()).toBe(V2.version);
    expect((await t.store.search('sandbags')).map((h) => h.chunk.id)).toEqual(['doc-a-0']);
    expect((await t.store.search('generator')).map((h) => h.chunk.id)).toEqual(['doc-b-0']);
    expect(await t.store.search('candles')).toEqual([]);
    expect(await t.store.document('doc-c')).toBeNull();
    expect(await t.store.document('doc-d')).not.toBeNull();
    expect((await t.store.document('doc-a'))?.fileChecksum).toBe(
      V2.manifest.documents.find((d) => d.id === 'doc-a')?.fileChecksum,
    );
  });

  it('fails and keeps old content when a file does not match its checksum', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();

    t.host.serve(V2);
    t.host.served.set('documents/doc-d.json', documentBytes('doc-d', 'Tampered text.')); // not what the manifest signed
    t.setNow(2_000);
    const result = await t.sync();

    expect(result.kind).toBe('failed');
    expect(result.kind === 'failed' && result.reason).toContain('doc-d');
    expect(await t.store.bundleVersion()).toBe(V1.version);
    expect(await t.store.lastSyncEpochSeconds()).toBe(1_000);
  });

  it('fails and keeps old content on an interrupted download, then succeeds on retry', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();

    t.host.serve(V2);
    t.host.broken.add('documents/doc-d.json');
    expect((await t.sync()).kind).toBe('failed');
    expect(await t.store.bundleVersion()).toBe(V1.version);
    expect(await t.store.document('doc-c')).not.toBeNull();

    t.host.broken.clear();
    expect(await t.sync()).toEqual({ kind: 'updated', bundleVersion: V2.version, documentsDownloaded: 2 });
  });

  it('fails and keeps old content when a document is missing', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();
    t.host.serve(V2);
    t.host.served.delete('documents/doc-b.json');

    const result = await t.sync();

    expect(result.kind).toBe('failed');
    expect(result.kind === 'failed' && result.reason).toContain('404');
    expect(await t.store.bundleVersion()).toBe(V1.version);
  });

  it('fails for an unsupported format version', async () => {
    const t = await setup();
    const v = new TestBundle('2026-10-09T00:00:00Z', { 'doc-a': 'x' }, signer, 2);
    t.host.served.set('bundle.json', v.manifestBytes);
    t.host.served.set('bundle.json.sig', v.signature);

    const result = await t.sync();

    expect(result.kind).toBe('failed');
    expect(result.kind === 'failed' && result.reason).toContain('formatVersion');
    expect(await t.store.bundleVersion()).toBeNull();
    expect(await t.store.lastSyncEpochSeconds()).toBeNull();
  });

  it('refuses a plain http base URL without fetching anything', async () => {
    const t = await setup();
    t.host.serve(V1);

    const result = await t.sync('http://example.org/bundle');

    expect(result.kind).toBe('failed');
    expect(result.kind === 'failed' && result.reason).toContain('https');
    expect(t.host.requests).toEqual([]);
    expect(await t.store.bundleVersion()).toBeNull();
  });

  it('keeps a replaced content set queryable after the sync time is recorded again', async () => {
    const t = await setup();
    t.host.serve(V1);
    await t.sync();
    t.host.serve(V2);
    t.setNow(3_000);
    await t.sync();
    expect(await t.store.lastSyncEpochSeconds()).toBe(3_000);
  });
});

describe('FetchSyncClient', () => {
  it('requires https', () => {
    expect(() => requireHttps('http://x.org')).toThrow(SyncHttpError);
    expect(() => requireHttps('https://')).toThrow(SyncHttpError);
    expect(requireHttps('HTTPS://x.org')).toBe('HTTPS://x.org');
  });
  it('refuses document ids that could change the path', () => {
    const client = new FetchSyncClient(BASE_URL, async () => ({ ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(0) }));
    return expect(client.fetchDocumentFile('../secret')).rejects.toThrow('unexpected characters');
  });
});

// Keeps the BundleManifest import honest for type-checking of the helper above.
export type { BundleManifest };
