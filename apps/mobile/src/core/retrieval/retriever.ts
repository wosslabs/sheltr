import type { StoreReader, StoredChunk, StoredDocument } from '../store/types';
import { countryFactor, hazardFactor } from './boost';
import { correctTypos, expandWithSynonyms } from './queryExpansion';
import { contentTerms, removeStopwords } from './stopwords';
import { synonymOf } from './vocabulary';

/**
 * Minimum keyword relevance (the negated FTS5 bm25 score, see {@link Passage.score}) the best passage must reach
 * for the app to answer. Below it the app refuses.
 *
 * The threshold is on the bm25 scale, which is unbounded and grows with term rarity. 1.0 is chosen on that
 * scale: FTS5 gives a term found in more than half of the chunks an idf of 1e-6, and a single occurrence of a term
 * found in about a quarter of the chunks scores near 1, so a query that matches only common words is refused
 * while one rare query word is enough to answer. This is a starting value; it is measured by the evaluation set
 * (see docs/evaluation.md).
 */
export const KEYWORD_RELEVANCE_THRESHOLD = 1.0;

/**
 * Share of the question's topical words that a passage must contain to count as an answer. Without it a single
 * generic word the corpus happens to use ("cover", "change", "world") is enough to answer an off-topic question.
 * Chosen with the evaluation set: at 0.5 two-word off-topic questions with one corpus word ("lose weight") still
 * pass; at 0.6 they are refused and nearly every answerable question of the set is still answered.
 */
export const MIN_TERM_COVERAGE = 0.6;

/** How much larger the ranked pool is when a boost applies. */
const POOL_MULTIPLIER = 5;

export interface RetrievalQuery {
  /** The user's words. FTS5 syntax in it is not interpreted. */
  readonly text: string;
  /** ISO 3166-1 alpha-2 code from settings, or null. */
  readonly country?: string | null;
  /** DisasterType names currently active for the user. */
  readonly activeHazards?: ReadonlySet<string>;
  readonly limit?: number;
}

export interface Passage {
  readonly chunk: StoredChunk;
  readonly document: StoredDocument;
  /** Keyword relevance: the FTS5 bm25 score negated, so higher is better. Compared with the threshold. */
  readonly score: number;
  /** {@link score} times the country and hazard factors. Used only for ordering, never for the threshold. */
  readonly rankScore: number;
}

export type RetrievalResult =
  /** Passages best first. At least one has a score at or above the threshold. */
  | { readonly kind: 'passages'; readonly passages: readonly Passage[] }
  /** No passage reached the threshold. The app shows no guidance. */
  | { readonly kind: 'refusal' };

export const REFUSAL: RetrievalResult = { kind: 'refusal' };

/**
 * Keyword retrieval: FTS5 ranking from the store, then a multiplicative country and hazard boost that only
 * reorders, then the relevance gate on the unboosted score.
 */
export class Retriever {
  constructor(
    private readonly store: StoreReader,
    private readonly threshold: number = KEYWORD_RELEVANCE_THRESHOLD,
  ) {}

  async retrieve(query: RetrievalQuery): Promise<RetrievalResult> {
    const passages = await this.rank(query);
    if (isAnswerable(passages, this.threshold, query.text)) return { kind: 'passages', passages };
    // Keyword search found nothing good enough: retry once with likely typos fixed, e.g. "eartquake" ->
    // "earthquake". Most queries have no typo to fix; skip the second search when correction is a no-op.
    const corrected = correctTypos(query.text);
    if (corrected === query.text) return REFUSAL;
    const correctedPassages = await this.rank({ ...query, text: corrected });
    return isAnswerable(correctedPassages, this.threshold, corrected)
      ? { kind: 'passages', passages: correctedPassages }
      : REFUSAL;
  }

  /** Ranked passages without the relevance gate. */
  async rank(query: RetrievalQuery): Promise<Passage[]> {
    const limit = query.limit ?? 5;
    if (!(limit > 0)) throw new RangeError('limit must be positive');
    const country = query.country ?? null;
    const activeHazards = query.activeHazards ?? new Set<string>();
    const boosting = country !== null || activeHazards.size > 0;
    // With a boost, rank a larger pool so a boosted passage just below the cut can move up.
    const pool = boosting ? limit * POOL_MULTIPLIER : limit;
    const documents = new Map<string, StoredDocument>();
    // Append known synonyms' canonical terms (e.g. "quake" -> also "earthquake") so the store search can match
    // either wording; stopwords are dropped so only topical words are searched.
    const hits = await this.store.search(removeStopwords(expandWithSynonyms(query.text)), pool);
    const passages: Passage[] = [];
    for (const hit of hits) {
      let document = documents.get(hit.chunk.documentId);
      if (document === undefined) {
        const found = await this.store.document(hit.chunk.documentId);
        if (found === null) throw new Error(`chunk ${hit.chunk.id} has no document`);
        document = found;
        documents.set(document.id, document);
      }
      const score = -hit.score;
      const factor = countryFactor(document.country, country) * hazardFactor(document.hazards, activeHazards);
      passages.push({ chunk: hit.chunk, document, score, rankScore: score * factor });
    }
    if (!boosting) return passages;
    // Array.prototype.sort is stable: equal boosted scores keep the keyword order.
    return passages.sort((a, b) => b.rankScore - a.rankScore).slice(0, limit);
  }
}

/**
 * True when some passage reaches [threshold] and also contains at least {@link MIN_TERM_COVERAGE} of the topical
 * words of [questionText]. No passages: false. A question with no topical words is never answerable.
 */
export function isAnswerable(passages: readonly Passage[], threshold: number, questionText: string): boolean {
  const terms = contentTerms(questionText);
  if (terms.size === 0) return false;
  return passages.some((p) => p.score >= threshold && coverage(terms, p.chunk.content) >= MIN_TERM_COVERAGE);
}

/** A term counts as present when the chunk has a form of it or, for a colloquial synonym, of the canonical term. */
function coverage(terms: ReadonlySet<string>, content: string): number {
  const present = [...contentTerms(content)];
  let covered = 0;
  for (const term of terms) {
    const canonical = synonymOf(term);
    if (present.some((p) => sameWord(term, p)) || (canonical !== undefined && present.some((p) => sameWord(canonical, p)))) {
      covered++;
    }
  }
  return covered / terms.size;
}

/**
 * True when [a] and [b] are the same word up to an ending ("aftershocks"/"aftershock", "evacuate"/"evacuation"):
 * they share a prefix of at least 4 letters that covers all but at most 1 letter of the shorter word. If both words
 * have exactly one letter left after the shared prefix, that is a substitution, not an ending ("flood"/"floor"),
 * so they differ. The store does no stemming, so exact comparison would refuse questions that merely use another
 * form of a word.
 */
export function sameWord(a: string, b: string): boolean {
  if (a === b) return true;
  let shared = 0;
  const max = Math.min(a.length, b.length);
  while (shared < max && a[shared] === b[shared]) shared++;
  if (shared < 4 || shared < Math.min(a.length, b.length) - 1) return false;
  return !(a.length - shared === 1 && b.length - shared === 1);
}
