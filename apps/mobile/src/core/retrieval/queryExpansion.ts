import { levenshtein } from './editDistance';
import { HAZARD_TERMS, SYNONYMS, synonymOf } from './vocabulary';
import { WORD, wordsOf } from './words';

/**
 * Shortest word eligible for typo correction, and shortest vocabulary entry eligible as a correction target. Below
 * this, ordinary English words land exactly one edit away from a short, generic term too often to correct safely —
 * e.g. "food"/"head"/"like" are each distance 1 from "flood"/"heat"/"lake". 7 is the smallest length at which no
 * term still collides this way (the one exception, "drought" vs "brought", is excluded separately below).
 */
const MIN_TYPO_LENGTH = 7;

/**
 * Largest edit distance at which a word is treated as a typo of a hazard term. Kept at 1 on purpose: at 2, real
 * words such as "water" (distance 2 from "winter") would be silently rewritten.
 */
const MAX_TYPO_DISTANCE = 1;

/** Valid correction targets: long enough terms, minus "drought", which is distance 1 from "brought". */
const CORRECTION_TARGETS: readonly string[] = [...HAZARD_TERMS].filter(
  (t) => t.length >= MIN_TYPO_LENGTH && t !== 'drought',
);

/**
 * Appends the canonical hazard term for each colloquial synonym in [text], so "quake" also searches "earthquake".
 * The original text is kept as written. A canonical term already present in [text] is not appended again.
 */
export function expandWithSynonyms(text: string): string {
  const words = wordsOf(text).map((w) => w.toLowerCase());
  const present = new Set(words);
  const expansions = new Set<string>();
  for (const word of words) {
    const canonical = synonymOf(word);
    if (canonical !== undefined && !present.has(canonical)) expansions.add(canonical);
  }
  return expansions.size === 0 ? text : `${text} ${[...expansions].join(' ')}`;
}

/**
 * Replaces each word that is a single edit away from exactly one hazard term with that term, so "eartquake"
 * searches "earthquake". Words that are already vocabulary terms or synonyms, shorter than the minimum length, or
 * ambiguous or unrelated to every term are left unchanged.
 */
export function correctTypos(text: string): string {
  return text.replace(WORD, (word) => correctWord(word));
}

function correctWord(word: string): string {
  const lower = word.toLowerCase();
  if (word.length < MIN_TYPO_LENGTH) return word;
  if (HAZARD_TERMS.has(lower) || Object.prototype.hasOwnProperty.call(SYNONYMS, lower)) return word;
  if (CORRECTION_TARGETS.length === 0) return word;

  const distances = CORRECTION_TARGETS.map((term) => ({ term, distance: levenshtein(lower, term) }));
  const minimum = Math.min(...distances.map((d) => d.distance));
  const nearest = distances.filter((d) => d.distance === minimum);
  // Ambiguous tie: unreachable with the real vocabulary today, kept so a future addition fails safe.
  return minimum <= MAX_TYPO_DISTANCE && nearest.length === 1 ? nearest[0].term : word;
}
