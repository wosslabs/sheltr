/** The extracted text of one page. Page numbers start at 1. */
export interface PageText {
  readonly pageNumber: number;
  readonly text: string;
}

export function pageText(pageNumber: number, text: string): PageText {
  if (!Number.isInteger(pageNumber) || pageNumber < 1) throw new RangeError('pageNumber must be >= 1');
  return { pageNumber, text };
}

/** Strips every character <= ' ' from both ends (control characters included), like Java's String.trim(). */
export function javaTrim(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && text.charCodeAt(start) <= 0x20) start++;
  while (end > start && text.charCodeAt(end - 1) <= 0x20) end--;
  return text.slice(start, end);
}

// Characters Java's Character.isWhitespace accepts, as code point ranges: not the non-breaking spaces U+00A0,
// U+2007 and U+202F. Built from numbers so no line separator appears literally in this file.
const JAVA_WHITESPACE: readonly (readonly [number, number])[] = [
  [0x09, 0x0d], [0x1c, 0x1f], [0x20, 0x20], [0x1680, 0x1680], [0x2000, 0x2006], [0x2008, 0x200a],
  [0x2028, 0x2029], [0x205f, 0x205f], [0x3000, 0x3000],
];

export function isBlank(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (!JAVA_WHITESPACE.some(([lo, hi]) => c >= lo && c <= hi)) return false;
  }
  return true;
}

/** Collapses runs of spaces and tabs, limits blank lines to one, and trims. */
export function clean(rawText: string): string {
  return javaTrim(rawText.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n'));
}
