import { BUNDLE_FORMAT_VERSION } from '../bundle/format';
import { bundle, doc, openStore } from '../testing/bundles';
import type { SearchHit, StoredChunk, StoredDocument, StoreReader } from '../store/types';
import { COUNTRY_BOOST } from './boost';
import { citationOf, selectCitations } from './citations';
import { KEYWORD_RELEVANCE_THRESHOLD, REFUSAL, Retriever, sameWord, type RetrievalQuery } from './retriever';

const document = (id: string, country: string | null, hazards: string[]): StoredDocument => ({
  id, title: `Title ${id}`, agency: 'Agency', publishedDate: '2024-01-01', licence: 'CC-BY-4.0', country, hazards,
  sourceUri: `https://example.org/${id}`, sourceType: 'html', checksum: '0'.repeat(64), fileChecksum: '1'.repeat(64),
});

const hit = (id: string, documentId: string, bm25: number): SearchHit => ({
  chunk: { id, documentId, content: `text ${id} water earthquake shelter quake`, pageNumber: null, position: 0 },
  score: bm25,
});

/** A store with a fixed keyword ranking (bm25 scores given directly). */
class FakeStore implements StoreReader {
  searchCalls: string[] = [];
  constructor(
    readonly docs: StoredDocument[],
    private readonly hits: SearchHit[] = [],
    private readonly respondTo?: Record<string, SearchHit[]>,
  ) {}
  async bundleVersion() { return 'test'; }
  async document(id: string) { return this.docs.find((d) => d.id === id) ?? null; }
  async documents() { return this.docs; }
  async chunks(documentId: string): Promise<StoredChunk[]> {
    return this.hits.map((h) => h.chunk).filter((c) => c.documentId === documentId);
  }
  async search(query: string, limit = 20) {
    this.searchCalls.push(query);
    return this.respondTo ? (this.respondTo[query] ?? []) : this.hits.slice(0, limit);
  }
}

describe('Retriever ranking and gate (fixed keyword ranking)', () => {
  // Keyword order a, b, c, d. Each boosted passage is within 15 % of the one above it.
  const docs = [document('global', null, ['FLOOD']), document('no', 'NO', ['STORM']), document('se', 'SE', ['WILDFIRE'])];
  const hits = [hit('a', 'global', -3.0), hit('b', 'se', -2.9), hit('c', 'no', -2.8), hit('d', 'global', -0.5)];
  const store = new FakeStore(docs, hits);
  const retriever = new Retriever(store);
  const ids = async (q: RetrievalQuery) => (await retriever.rank(q)).map((p) => p.chunk.id);

  it('keeps the keyword order without a country or hazards', async () => {
    expect(await ids({ text: 'water', limit: 3 })).toEqual(['a', 'b', 'c']);
    expect((await retriever.rank({ text: 'water', limit: 4 })).map((p) => -p.score)).toEqual(hits.map((h) => h.score));
  });

  it('moves a country match up and removes nothing', async () => {
    // c: 2.8 * 1.15 = 3.22 passes a (3.0) and b (2.9).
    expect(await ids({ text: 'water', country: 'NO', limit: 4 })).toEqual(['c', 'a', 'b', 'd']);
    expect(await ids({ text: 'water', country: 'FI', limit: 4 })).toEqual(['a', 'b', 'c', 'd']);
  });

  it('keeps global passages when a country is set', async () => {
    const ranked = await retriever.rank({ text: 'water', country: 'SE', limit: 4 });
    expect(ranked.map((p) => p.chunk.id)).toEqual(['b', 'a', 'c', 'd']);
    expect(ranked.filter((p) => p.document.country === null).map((p) => p.chunk.id)).toEqual(['a', 'd']);
  });

  it('boosts an active hazard without filtering', async () => {
    expect(await ids({ text: 'water', activeHazards: new Set(['WILDFIRE']), limit: 4 })).toEqual(['b', 'a', 'c', 'd']);
    // Country and hazard multiply: c is 2.8 * 1.15 * 1.15 = 3.70, b is 2.9 * 1.15 = 3.34.
    expect(
      await ids({ text: 'water', country: 'NO', activeHazards: new Set(['WILDFIRE', 'STORM']), limit: 4 }),
    ).toEqual(['c', 'b', 'a', 'd']);
  });

  it('lets a boosted passage below the cut enter the results', async () => {
    expect(await ids({ text: 'water', limit: 2 })).toEqual(['a', 'b']);
    expect(await ids({ text: 'water', country: 'NO', limit: 2 })).toEqual(['c', 'a']);
  });

  it('refuses when the best passage is below the threshold', async () => {
    const weak = new Retriever(new FakeStore(docs, [hit('d', 'global', -0.999)]));
    expect(await weak.retrieve({ text: 'water' })).toBe(REFUSAL);
    expect(await new Retriever(new FakeStore(docs, [])).retrieve({ text: 'x' })).toBe(REFUSAL);
  });

  it('answers when the best passage is exactly at the threshold, and returns weaker passages too', async () => {
    const atThreshold = new Retriever(
      new FakeStore(docs, [hit('d', 'global', -KEYWORD_RELEVANCE_THRESHOLD), hit('e', 'no', -0.2)]),
    );
    const result = await atThreshold.retrieve({ text: 'water' });
    expect(result.kind).toBe('passages');
    expect(result.kind === 'passages' && result.passages.map((p) => p.chunk.id)).toEqual(['d', 'e']);
  });

  it('never lets a boost lift a passage over the threshold', async () => {
    // 0.9 * 1.15 * 1.15 = 1.19 would pass if the gate used the boosted score; it uses the keyword score.
    const boosted = new Retriever(new FakeStore(docs, [hit('c', 'no', -0.9)]));
    expect(await boosted.retrieve({ text: 'water', country: 'NO', activeHazards: new Set(['STORM']) })).toBe(REFUSAL);
  });

  it('has its threshold on the bm25 scale', () => expect(KEYWORD_RELEVANCE_THRESHOLD).toBe(1.0));

  it('corrects a typo when the first search refuses', async () => {
    const typoStore = new FakeStore([document('d', null, ['EARTHQUAKE'])], [], {
      'eartquake shelter': [],
      'earthquake shelter': [hit('a', 'd', -5.0)], // well above threshold once negated
    });
    const result = await new Retriever(typoStore).retrieve({ text: 'eartquake shelter' });
    expect(result.kind === 'passages' && result.passages[0].chunk.id).toBe('a');
    // Proves the retry re-queried with the corrected text.
    expect(typoStore.searchCalls).toEqual(['eartquake shelter', 'earthquake shelter']);
  });

  it('searches the synonym-expanded text', async () => {
    const synStore = new FakeStore([document('d', null, ['EARTHQUAKE'])], [], { 'quake earthquake': [hit('a', 'd', -5.0)] });
    expect((await new Retriever(synStore).rank({ text: 'quake' })).map((p) => p.chunk.id)).toEqual(['a']);
  });

  it('does not retry when correction does not change the query', async () => {
    const empty = new FakeStore([], [], {});
    expect(await new Retriever(empty).retrieve({ text: 'water' })).toBe(REFUSAL);
    expect(empty.searchCalls).toHaveLength(1);
  });

  it('never answers a question made only of stopwords', async () => {
    const s = new FakeStore(docs, hits);
    expect(await new Retriever(s).retrieve({ text: 'how do I' })).toBe(REFUSAL);
  });

  it('rejects a non-positive limit', async () => {
    await expect(retriever.rank({ text: 'water', limit: 0 })).rejects.toThrow(RangeError);
  });
});

describe('Retriever over the real FTS5 store', () => {
  const docs = [
    // The global chunk is the better keyword match for "boil water"; the Norwegian one is close behind.
    doc('global', 'Global guide', null, [['Boil water before drinking. Boil it for one full minute.', null]], ['DROUGHT']),
    doc('norway', 'Norway guide', 'NO', [['Boil water before drinking it after the storm passes.', null]], ['STORM']),
    doc('sweden', 'Sweden guide', 'SE', [['Boil water before drinking it after the flood recedes.', null]], ['FLOOD']),
    ...Array.from({ length: 20 }, (_, i) =>
      doc(`filler-${i + 1}`, `Filler ${i + 1}`, 'US', [[`Keep the kit ready and check the plan number ${i + 1}.`, null]], ['HEATWAVE']),
    ),
  ];

  async function setup() {
    const { store } = await openStore();
    const b = bundle('2026-10-05T12:00:00Z', ...docs);
    await store.install({ ...b.manifest, formatVersion: BUNDLE_FORMAT_VERSION }, b.source);
    return { store, retriever: new Retriever(store) };
  }
  const docIds = async (r: Retriever, q: RetrievalQuery) => (await r.rank(q)).map((p) => p.chunk.documentId);

  it('orders by plain keyword ranking without a country or hazards', async () => {
    const { store, retriever } = await setup();
    const plain = await store.search('boil water', 5);
    const ranked = await retriever.rank({ text: 'boil water', limit: 5 });
    expect(ranked.map((p) => p.chunk.id)).toEqual(plain.map((h) => h.chunk.id));
    expect(ranked.map((p) => p.score)).toEqual(plain.map((h) => -h.score));
    expect(ranked[0].chunk.documentId).toBe('global');
  });

  it('boosts a country match above a close global match', async () => {
    const { retriever } = await setup();
    const plain = await retriever.rank({ text: 'boil water', limit: 3 });
    const global = plain.find((p) => p.document.id === 'global')!;
    const norway = plain.find((p) => p.document.id === 'norway')!;
    expect(global.score).toBeGreaterThan(norway.score);
    expect(norway.score * (1 + COUNTRY_BOOST)).toBeGreaterThan(global.score);
    expect((await docIds(retriever, { text: 'boil water', country: 'NO', limit: 3 }))[0]).toBe('norway');
  });

  it('keeps the global passage when any country is set', async () => {
    const { retriever } = await setup();
    for (const country of ['NO', 'SE', 'US', 'FI']) {
      expect(new Set(await docIds(retriever, { text: 'boil water', country, limit: 3 }))).toEqual(
        new Set(['global', 'norway', 'sweden']),
      );
    }
  });

  it('boosts the document of an active hazard', async () => {
    const { retriever } = await setup();
    expect((await docIds(retriever, { text: 'boil water', limit: 3 }))[0]).toBe('global');
    expect((await docIds(retriever, { text: 'boil water', activeHazards: new Set(['FLOOD']), limit: 3 }))[0]).toBe('sweden');
    expect((await docIds(retriever, { text: 'boil water', activeHazards: new Set(['STORM']), limit: 3 }))[0]).toBe('norway');
    expect(await docIds(retriever, { text: 'boil water', activeHazards: new Set(['EARTHQUAKE']), limit: 3 })).toEqual(
      await docIds(retriever, { text: 'boil water', limit: 3 }),
    );
  });

  it('answers a rare-word question at the default threshold', async () => {
    const { retriever } = await setup();
    const result = await retriever.retrieve({ text: 'boil water' });
    expect(result.kind).toBe('passages');
    expect(result.kind === 'passages' && result.passages[0].score).toBeGreaterThanOrEqual(KEYWORD_RELEVANCE_THRESHOLD);
  });

  it('refuses stopwords and words in no chunk', async () => {
    const { retriever } = await setup();
    expect(await retriever.rank({ text: 'the how do I', limit: 3 })).toEqual([]);
    expect(await retriever.retrieve({ text: 'the' })).toBe(REFUSAL);
    expect(await retriever.retrieve({ text: 'volcano' })).toBe(REFUSAL);
  });

  it('takes citations from retrieved passages', async () => {
    const { retriever } = await setup();
    const result = await retriever.retrieve({ text: 'boil water', limit: 3 });
    if (result.kind !== 'passages') throw new Error('expected passages');
    const passages = result.passages;
    const used = selectCitations('Boil it [2].', passages, passages.map(citationOf));
    expect(used.map((c) => c.chunkId)).toEqual([passages[1].chunk.id]);
    expect(used[0].sourceTitle).toBe(passages[1].document.title);
  });
});

describe('sameWord', () => {
  it('matches word forms that differ by an ending', () => {
    expect(sameWord('aftershocks', 'aftershock')).toBe(true);
    expect(sameWord('evacuate', 'evacuation')).toBe(true);
    expect(sameWord('disasters', 'disaster')).toBe(true);
    expect(sameWord('heat', 'heating')).toBe(true);
  });
  it('does not match unrelated or only loosely related words', () => {
    expect(sameWord('programming', 'programmes')).toBe(false);
    expect(sameWord('pet', 'petrol')).toBe(false);
    expect(sameWord('flood', 'floor')).toBe(false);
    expect(sameWord('heat', 'heal')).toBe(false);
  });
});
