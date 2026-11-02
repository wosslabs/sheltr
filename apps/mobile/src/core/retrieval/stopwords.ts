import rules from '../../../../../contract/retrieval-rules.json';
import { wordsOf } from './words';

/**
 * English function and question words. They carry no topic, but appear in many chunks, so searching them lets an
 * off-topic question ("how do I file my taxes") collect enough keyword score to pass the relevance gate. The corpus
 * is English; revisit this list if non-English documents are added. The list is contract/retrieval-rules.json.
 */
const STOPWORDS: ReadonlySet<string> = new Set(rules.stopwords);

/**
 * Removes stopwords from [text], keeping the remaining words in order, separated by single spaces. Returns an
 * empty string when nothing is left, which the store treats as a query with no words.
 */
export function removeStopwords(text: string): string {
  return wordsOf(text)
    .filter((w) => !STOPWORDS.has(w.toLowerCase()))
    .join(' ');
}

/** Distinct lower-case topical words of [text]: its words without stopwords. */
export function contentTerms(text: string): Set<string> {
  const terms = new Set<string>();
  for (const w of wordsOf(text)) {
    const lower = w.toLowerCase();
    if (!STOPWORDS.has(lower)) terms.add(lower);
  }
  return terms;
}
