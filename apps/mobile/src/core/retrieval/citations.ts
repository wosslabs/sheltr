import type { Passage } from './retriever';

/** A source shown with an answer. */
export interface Citation {
  readonly documentId: string;
  readonly chunkId: string;
  readonly sourceTitle: string;
  readonly sourceUrl: string;
  readonly pageNumber: number | null;
}

export function citationOf(passage: Passage): Citation {
  return {
    documentId: passage.document.id,
    chunkId: passage.chunk.id,
    sourceTitle: passage.document.title,
    sourceUrl: passage.document.sourceUri,
    pageNumber: passage.chunk.pageNumber,
  };
}

const MARKER = /\[([0-9]{1,3})]/g;

/**
 * Finds which numbered passages an answer cites, written as [1], [2], and so on. Markers are 1-based passage
 * numbers of one to three digits; numbers outside 1..passages.length are ignored; the result is the [citations]
 * whose chunk was cited, in the order of [citations], each once.
 */
export function selectCitations(
  answer: string | null | undefined,
  passages: readonly Passage[],
  citations: readonly Citation[],
): Citation[] {
  return selectByChunkIds(answer, passages.map((p) => p.chunk.id), citations);
}

/** The same rule over the chunk ids of the numbered passages, in prompt order. */
export function selectByChunkIds(
  answer: string | null | undefined,
  passageChunkIds: readonly string[],
  citations: readonly Citation[],
): Citation[] {
  const cited = new Set<string>();
  for (const match of (answer ?? '').matchAll(MARKER)) {
    const number = Number(match[1]);
    if (number >= 1 && number <= passageChunkIds.length) cited.add(passageChunkIds[number - 1]);
  }
  return citations.filter((c) => cited.has(c.chunkId));
}
