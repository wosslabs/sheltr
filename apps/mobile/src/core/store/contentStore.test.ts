import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { BUNDLE_FORMAT_VERSION } from '../bundle/format';
import { bundle, doc, openStore } from '../testing/bundles';
import { NodeSqliteDatabase } from '../testing/nodeSqlite';
import { ContentStore, StoreUpdateError } from './contentStore';
import { ftsQueryFor } from './ftsQuery';

const V1 = bundle(
  '2026-10-01T00:00:00Z',
  doc('doc-a', 'Flood guide', 'NO', [['Move to higher ground.', 1], ['Place sandbags at doors.', 2]]),
  doc('doc-b', 'Evacuation', null, [['Follow the evacuation route.', null]]),
);

const V2 = bundle(
  '2026-10-05T12:00:00Z',
  doc('doc-b', 'Evacuation', null, [['Follow the evacuation route.', null]]),
  doc('doc-c', 'Wildfire smoke', 'CA', [['Stay indoors.', 1], ['Wear a respirator outside.', 1]], ['WILDFIRE', 'HEATWAVE', 'WILDFIRE']),
);

/** Everything a reader can see, for before/after comparison. */
async function snapshot(store: ContentStore) {
  const ids = ['doc-a', 'doc-b', 'doc-c'];
  return {
    version: await store.bundleVersion(),
    documents: await Promise.all(ids.map((id) => store.document(id))),
    chunks: await Promise.all(ids.map((id) => store.chunks(id))),
    hits: (await store.search('sandbags evacuation respirator')).map((h) => h.chunk.id).sort(),
  };
}

describe('ContentStore', () => {
  it('starts empty', async () => {
    const { store } = await openStore();
    expect(await store.bundleVersion()).toBeNull();
    expect(await store.search('flood')).toEqual([]);
    expect(await store.document('doc-a')).toBeNull();
    expect(await store.documents()).toEqual([]);
  });

  it('lists every document with its hazards, ordered by title', async () => {
    const { store } = await openStore();
    await store.install(V2.manifest, V2.source);
    const documents = await store.documents();
    expect(documents.map((d) => d.title)).toEqual([...documents.map((d) => d.title)].sort());
    expect(new Set(documents.map((d) => d.id))).toEqual(new Set(V2.manifest.documents.map((d) => d.id)));
    for (const listed of documents) expect(await store.document(listed.id)).toEqual(listed);
  });

  it('makes new content queryable after a replace', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);
    expect(await store.bundleVersion()).toBe('2026-10-01T00:00:00Z');

    await store.install(V2.manifest, V2.source);

    expect(await store.bundleVersion()).toBe('2026-10-05T12:00:00Z');
    expect(await store.document('doc-a')).toBeNull();
    const c = await store.document('doc-c');
    expect(c?.title).toBe('Wildfire smoke');
    expect(c?.country).toBe('CA');
    expect((await store.chunks('doc-c')).map((x) => x.id)).toEqual(['doc-c-0', 'doc-c-1']);
    expect((await store.search('respirator')).map((h) => h.chunk.id)).toEqual(['doc-c-1']);
    expect(await store.search('sandbags')).toEqual([]);
  });

  it('keeps the previous content when loading a document fails', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);
    const before = await snapshot(store);

    let loaded = 0;
    await expect(
      store.install(V2.manifest, (d) => {
        if (++loaded === 2) throw new Error(`connection reset while reading ${d.id}`);
        return V2.source(d);
      }),
    ).rejects.toThrow('connection reset');

    expect(loaded).toBe(2);
    expect(await snapshot(store)).toEqual(before);
  });

  it('rolls back and keeps the old content when a chunk is missing', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);
    const before = await snapshot(store);

    const short = (d: Parameters<typeof V2.source>[0]) => {
      const file = V2.source(d) as ReturnType<typeof V2.source> & { chunks: unknown[] };
      return d.id === 'doc-c' ? { ...file, chunks: file.chunks.slice(0, -1) } : file;
    };
    await expect(store.install(V2.manifest, short as typeof V2.source)).rejects.toThrow(
      new StoreUpdateError('staged 2 chunks, manifest lists 3'),
    );

    expect(await snapshot(store)).toEqual(before);
  });

  it('rolls back when a manifest entry is duplicated', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);
    const before = await snapshot(store);
    const manifest = { ...V2.manifest, documents: [...V2.manifest.documents, V2.manifest.documents[0]] };

    await expect(store.install(manifest, V2.source)).rejects.toThrow();

    expect(await snapshot(store)).toEqual(before);
  });

  it('rejects a file whose id does not match the manifest entry', async () => {
    const { store } = await openStore();
    await expect(
      store.install(V1.manifest, () => ({ id: 'other', chunks: [] })),
    ).rejects.toThrow('does not match manifest entry');
    expect(await store.bundleVersion()).toBeNull();
  });

  it('rejects an unsupported format version', async () => {
    const { store } = await openStore();
    await expect(store.install({ ...V1.manifest, formatVersion: BUNDLE_FORMAT_VERSION + 1 }, V1.source)).rejects.toThrow(
      'unsupported bundle format',
    );
  });

  it('finds present words and not absent ones', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);

    const hits = await store.search('sandbags');
    expect(hits.map((h) => h.chunk.id)).toEqual(['doc-a-1']);
    expect(hits[0].chunk.documentId).toBe('doc-a');
    expect(hits[0].chunk.pageNumber).toBe(2);

    expect(await store.search('volcano')).toEqual([]);
    expect(await store.search('  -- "* ')).toEqual([]);
    // FTS5 syntax in user text is treated as words, not operators.
    expect((await store.search('sandbags NOT')).map((h) => h.chunk.id)).toEqual(['doc-a-1']);
    expect((await store.search('sandbags AND (')).map((h) => h.chunk.id)).toEqual(['doc-a-1']);
  });

  it('changes the bundle version only on a successful replace', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);

    await expect(store.install(V2.manifest, () => { throw new Error('offline'); })).rejects.toThrow('offline');
    expect(await store.bundleVersion()).toBe('2026-10-01T00:00:00Z');

    await expect(store.install(V2.manifest, (d) => ({ ...(V2.source(d) as object), chunks: [] }) as never)).rejects.toThrow(
      StoreUpdateError,
    );
    expect(await store.bundleVersion()).toBe('2026-10-01T00:00:00Z');

    await store.install(V2.manifest, V2.source);
    expect(await store.bundleVersion()).toBe('2026-10-05T12:00:00Z');
  });

  it('persists content across a reopen of the same database file', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sheltr-store-'));
    const file = path.join(dir, 'content.db');
    try {
      const first = await ContentStore.open(new NodeSqliteDatabase(file));
      await first.install(V1.manifest, V1.source);
      const reopened = await ContentStore.open(new NodeSqliteDatabase(file));
      expect(await reopened.bundleVersion()).toBe('2026-10-01T00:00:00Z');
      expect((await reopened.search('sandbags')).map((h) => h.chunk.id)).toEqual(['doc-a-1']);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('round-trips hazards, stored once and read back sorted', async () => {
    const { store } = await openStore();
    await store.install(V1.manifest, V1.source);
    expect((await store.document('doc-a'))?.hazards).toEqual(['FLOOD']);

    await store.install(V2.manifest, V2.source);

    expect((await store.document('doc-c'))?.hazards).toEqual(['HEATWAVE', 'WILDFIRE']);
    expect((await store.document('doc-b'))?.hazards).toEqual(['FLOOD']);
  });

  it('gives a document without hazards an empty list', async () => {
    const { store } = await openStore();
    const { entry, file } = doc('doc-x', 'No hazards', null, [['Keep a kit.', null]], []);
    await store.install({ formatVersion: BUNDLE_FORMAT_VERSION, bundleVersion: '2026-10-06T00:00:00Z', documents: [entry] }, () => file);
    expect((await store.document('doc-x'))?.hazards).toEqual([]);
  });

  it('stores the last sync time with the active content', async () => {
    const { store } = await openStore();
    expect(await store.lastSyncEpochSeconds()).toBeNull();
    await expect(store.recordSync(10)).rejects.toThrow(StoreUpdateError);

    await store.install(V1.manifest, V1.source);
    expect(await store.lastSyncEpochSeconds()).toBeNull();
    await store.recordSync(1_700_000_000);
    expect(await store.lastSyncEpochSeconds()).toBe(1_700_000_000);
    expect(await store.bundleVersion()).toBe('2026-10-01T00:00:00Z');
  });
});

describe('ftsQueryFor', () => {
  it('quotes every word once', () => expect(ftsQueryFor('flood? water* flood')).toBe('"flood" OR "water"'));
  it('returns null without words', () => expect(ftsQueryFor('"" ( ) *')).toBeNull());
});
