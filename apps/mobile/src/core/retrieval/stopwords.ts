import { wordsOf } from './words';

/**
 * English function and question words. They carry no topic, but appear in many chunks, so searching them lets an
 * off-topic question ("how do I file my taxes") collect enough keyword score to pass the relevance gate. The corpus
 * is English; revisit this list if non-English documents are added.
 */
const STOPWORDS: ReadonlySet<string> = new Set([
  'a', 'about', 'after', 'again', 'all', 'am', 'an', 'and', 'any', 'are', 'as', 'at', 'be', 'because', 'been',
  'before', 'being', 'but', 'by', 'can', 'could', 'did', 'do', 'does', 'doing', 'done', 'during', 'for', 'from',
  'get', 'got', 'had', 'has', 'have', 'having', 'he', 'her', 'here', 'him', 'his', 'how', 'i', 'if', 'in', 'into',
  'is', 'it', 'its', 'just', 'keep', 'know', 'let', 'make', 'me', 'more', 'most', 'my', 'need', 'no', 'nor', 'not',
  'now', 'of', 'off', 'on', 'once', 'only', 'or', 'other', 'our', 'out', 'over', 'own', 'same', 'she', 'should',
  'so', 'some', 'such', 'take', 'than', 'that', 'the', 'their', 'them', 'then', 'there', 'these', 'they', 'this',
  'those', 'through', 'to', 'too', 'under', 'until', 'up', 'use', 'very', 'was', 'we', 'were', 'what', 'when',
  'where', 'which', 'while', 'who', 'want', 'whom', 'why', 'will', 'with', 'would', 'you', 'your',
]);

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
