import { countryFactor, hazardFactor } from './boost';
import { citationOf, selectByChunkIds, type Citation } from './citations';
import { levenshtein } from './editDistance';
import { correctTypos, expandWithSynonyms } from './queryExpansion';
import { contentTerms, removeStopwords } from './stopwords';
import { HAZARD_TERMS, SYNONYMS } from './vocabulary';

describe('levenshtein', () => {
  it('is zero for identical strings', () => expect(levenshtein('earthquake', 'earthquake')).toBe(0));
  it('matches kitten/sitting', () => expect(levenshtein('kitten', 'sitting')).toBe(3));
  it('is the length against an empty string', () => expect(levenshtein('', 'abc')).toBe(3));
  it('counts a single substitution', () => expect(levenshtein('eartquake', 'earthquake')).toBe(1));
});

describe('vocabulary', () => {
  it('covers every disaster type word', () => {
    for (const term of ['earthquake', 'flood', 'tropical', 'cyclone', 'tornado', 'tsunami', 'wildfire', 'drought']) {
      expect(HAZARD_TERMS.has(term)).toBe(true);
    }
  });
  it('maps every synonym onto a term', () => {
    expect(SYNONYMS.quake).toBe('earthquake');
    expect(SYNONYMS.twister).toBe('tornado');
    for (const canonical of Object.values(SYNONYMS)) expect(HAZARD_TERMS.has(canonical)).toBe(true);
  });
});

describe('boost', () => {
  it('boosts a matching country', () => expect(countryFactor('GB', 'GB')).toBeCloseTo(1.15, 9));
  it('does not boost global or other-country passages', () => {
    expect(countryFactor(null, 'GB')).toBe(1);
    expect(countryFactor('US', 'GB')).toBe(1);
  });
  it('does not boost without a query country', () => expect(countryFactor('GB', null)).toBe(1));
  it('boosts a document about an active hazard', () =>
    expect(hazardFactor(['FLOOD', 'STORM'], new Set(['FLOOD']))).toBeCloseTo(1.15, 9));
  it('leaves other documents and hazard-free queries unchanged', () => {
    expect(hazardFactor(['WILDFIRE'], new Set(['FLOOD']))).toBe(1);
    expect(hazardFactor(['FLOOD'], new Set())).toBe(1);
    expect(hazardFactor([], new Set(['FLOOD']))).toBe(1);
  });
});

describe('selectByChunkIds', () => {
  const passages = ['chunk-1', 'chunk-2', 'chunk-3'];
  const citation = (chunkId: string, title: string): Citation => ({
    documentId: `doc-${chunkId}`, chunkId, sourceTitle: title, sourceUrl: 'https://example.org', pageNumber: null,
  });
  const citations = [citation('chunk-1', 'One'), citation('chunk-2', 'Two'), citation('chunk-3', 'Three')];

  it('keeps only the passages the answer cites', () => {
    const used = selectByChunkIds('Boil it [3]. Then cool it [1].', passages, citations);
    expect(used.map((c) => c.sourceTitle)).toEqual(['One', 'Three']);
  });
  it('ignores passage numbers that do not exist', () =>
    expect(selectByChunkIds('Boil it [7]. Or [0].', passages, citations)).toEqual([]));
  it('returns nothing when the answer cites nothing', () => {
    expect(selectByChunkIds('Boil it for one minute.', passages, citations)).toEqual([]);
    expect(selectByChunkIds(null, passages, citations)).toEqual([]);
  });
  it('skips a cited passage whose document had no citation', () => {
    const withoutSecond = [citation('chunk-1', 'One'), citation('chunk-3', 'Three')];
    expect(selectByChunkIds('See [2] and [3].', passages, withoutSecond).map((c) => c.sourceTitle)).toEqual(['Three']);
  });
  it('builds a citation from a passage', () => {
    const c = citationOf({
      chunk: { id: 'c', documentId: 'd', content: 'x', pageNumber: 4, position: 0 },
      document: {
        id: 'd', title: 'T', agency: 'A', publishedDate: '2024-01-01', licence: 'L', country: null, hazards: [],
        sourceUri: 'https://e.org', sourceType: 'pdf', checksum: '', fileChecksum: '',
      },
      score: 1, rankScore: 1,
    });
    expect(c).toEqual({ documentId: 'd', chunkId: 'c', sourceTitle: 'T', sourceUrl: 'https://e.org', pageNumber: 4 });
  });
});

describe('query expansion', () => {
  it('appends the canonical term for a synonym', () =>
    expect(expandWithSynonyms('quake near the river')).toBe('quake near the river earthquake'));
  it('is a no-op without a synonym', () =>
    expect(expandWithSynonyms('water near the river')).toBe('water near the river'));
  it('does not duplicate a canonical term already present', () =>
    expect(expandWithSynonyms('earthquake quake')).toBe('earthquake quake'));
  it('appends each canonical term once', () => expect(expandWithSynonyms('quake quake')).toBe('quake quake earthquake'));
  it('fixes a single typo', () => expect(correctTypos('eartquake shelter')).toBe('earthquake shelter'));
  it('leaves correctly spelled words alone', () => expect(correctTypos('water shelter')).toBe('water shelter'));
  it('leaves exact vocabulary words alone', () => expect(correctTypos('earthquake')).toBe('earthquake'));
  it('leaves short words alone', () => expect(correctTypos('lak')).toBe('lak'));
  it('leaves unrelated misspellings alone', () => expect(correctTypos('zzzzz')).toBe('zzzzz'));
  it('leaves common English words alone', () => {
    for (const word of ['food', 'head', 'like', 'make', 'blood', 'floor', 'store', 'brought', 'winner']) {
      expect(correctTypos(word)).toBe(word);
    }
  });
});

describe('stopwords', () => {
  it('removes function words and keeps topical ones in order', () =>
    expect(removeStopwords('How do I stay safe in an earthquake?')).toBe('stay safe earthquake'));
  it('returns an empty string when only stopwords remain', () => expect(removeStopwords('the how do I')).toBe(''));
  it('lists distinct lower-case topical terms', () => {
    expect([...contentTerms('Flood FLOOD water the')]).toEqual(['flood', 'water']);
  });
});
