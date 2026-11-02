import { isBlank, javaTrim, type PageText } from './text.ts';

/** A chunk of document text ready to store. pageNumber is the 1-based source page, or null for HTML. */
export interface ChunkDraft {
  readonly content: string;
  readonly pageNumber: number | null;
}

export function chunkDraft(content: string, pageNumber: number | null): ChunkDraft {
  if (isBlank(content)) throw new RangeError('ChunkDraft content must not be blank');
  if (pageNumber !== null && !(pageNumber >= 1)) throw new RangeError('pageNumber must be >= 1');
  return { content, pageNumber };
}

const CHUNK_SIZE = 1000;
const OVERLAP = 100;

/** Splits text into chunks of at most about 1000 characters that overlap by about 100, cut on word boundaries. */
export function chunkPages(pages: readonly PageText[]): ChunkDraft[] {
  return pages.flatMap((page) => chunkText(page.text, page.pageNumber));
}

/** Chunks a document that has no pages (HTML). Its chunks carry no page number. */
export function chunkUnpaged(text: string): ChunkDraft[] {
  return chunkText(text, null);
}

function chunkText(text: string, pageNumber: number | null): ChunkDraft[] {
  const drafts: ChunkDraft[] = [];
  let start = 0;
  while (start < text.length) {
    let end = Math.min(start + CHUNK_SIZE, text.length);
    if (end < text.length) {
      const lastSpace = text.lastIndexOf(' ', end);
      if (lastSpace > start) end = lastSpace;
      // else: no space in [start, end] (one token longer than CHUNK_SIZE). Last-resort fallback: hard-cut
      // mid-word rather than searching backward without bound.
    }
    let nextStart = end >= text.length ? text.length : Math.max(end - OVERLAP, start + 1);
    if (end < text.length && nextStart > 0 && text[nextStart - 1] !== ' ') {
      // end - OVERLAP can land inside a word. Search forward, unbounded, for the next space and snap just past it.
      const forwardSpace = text.indexOf(' ', nextStart);
      if (forwardSpace >= 0) {
        // The next safe boundary sits beyond this chunk's end (a long unbroken token spans the overlap window).
        // Extend this chunk to cover that span rather than skipping it, which would drop every character between.
        if (forwardSpace >= end) end = forwardSpace;
        nextStart = forwardSpace + 1;
      }
      // else: no space in the rest of the text. Keep the unadjusted position.
    }
    const trimmed = javaTrim(text.slice(start, end));
    if (!isBlank(trimmed)) drafts.push(chunkDraft(trimmed, pageNumber));
    if (end >= text.length) break;
    start = nextStart;
  }
  return drafts;
}
