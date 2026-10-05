package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;

import java.util.ArrayList;
import java.util.List;

public final class Chunker {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP = 100;

    private Chunker() {
    }

    public static List<ChunkDraft> chunk(List<PageText> pages) {
        List<ChunkDraft> drafts = new ArrayList<>();
        for (PageText page : pages) {
            drafts.addAll(chunkText(page.text(), new PageNumber(page.pageNumber())));
        }
        return drafts;
    }

    /** Chunks a document that has no pages (HTML). Its chunks carry no page number. */
    public static List<ChunkDraft> chunkUnpaged(String text) {
        return chunkText(text, null);
    }

    private static List<ChunkDraft> chunkText(String text, PageNumber pageNumber) {
        List<ChunkDraft> drafts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
                // else: no space found in [start, end] (a single token longer than
                // CHUNK_SIZE). This is an intentional last-resort fallback that hard-cuts
                // mid-word rather than searching further backward without bound. Real
                // prose essentially never contains a token this long, so this branch is
                // accepted as-is rather than fixed.
            }

            int nextStart = end >= text.length() ? text.length() : Math.max(end - OVERLAP, start + 1);
            if (end < text.length() && nextStart > 0 && text.charAt(nextStart - 1) != ' ') {
                // end - OVERLAP can land inside a word in non-periodic prose. Search
                // forward, unbounded (not limited to the current chunk's end), for the
                // next space and snap to just past it so the next chunk starts on a
                // word boundary.
                int forwardSpace = text.indexOf(' ', nextStart);
                if (forwardSpace >= 0) {
                    if (forwardSpace >= end) {
                        // The next safe word boundary sits beyond this chunk's current
                        // end (e.g. a long unbroken token - URL, DOI, identifier - spans
                        // the overlap window). Extend this chunk's end to cover that span
                        // instead of just jumping the next chunk's start past it, which
                        // would silently drop every character in between. Only the space
                        // character itself (already trimmed/normalized elsewhere) is
                        // excluded from both chunks - no real content is lost.
                        end = forwardSpace;
                    }
                    nextStart = forwardSpace + 1;
                }
                // else: no space exists anywhere in the rest of the page's text. Fall
                // back to the unadjusted position; this last-resort fallback mirrors the
                // single-long-token fallback above.
            }

            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                drafts.add(new ChunkDraft(chunkText, pageNumber));
            }
            if (end >= text.length()) {
                break;
            }
            start = nextStart;
        }
        return drafts;
    }
}
