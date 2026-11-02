/** A word is a maximal run of letters or digits. Everything between words is kept exactly as written. */
export const WORD = /[\p{L}\p{N}]+/gu;

/** Words of [text], in order and as written. */
export function wordsOf(text: string): string[] {
  return Array.from(text.matchAll(WORD), (m) => m[0]);
}
