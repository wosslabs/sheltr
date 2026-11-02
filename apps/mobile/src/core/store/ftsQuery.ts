/**
 * Builds an FTS5 query that matches any of the words in [text]. Each word is quoted, so operators, column
 * filters and quotes in user text are never interpreted. Returns null if [text] has no words.
 */
export function ftsQueryFor(text: string): string | null {
  const words = Array.from(text.matchAll(/[\p{L}\p{N}]+/gu), (m) => m[0]);
  if (words.length === 0) return null;
  return [...new Set(words)].map((w) => `"${w}"`).join(' OR ');
}
