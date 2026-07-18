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
            String text = page.text();
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
                String chunkText = text.substring(start, end).trim();
                if (!chunkText.isBlank()) {
                    drafts.add(new ChunkDraft(chunkText, new PageNumber(page.pageNumber())));
                }
                if (end >= text.length()) {
                    break;
                }
                int nextStart = Math.max(end - OVERLAP, start + 1);
                boolean nextStartIsMidWord = nextStart > 0 && text.charAt(nextStart - 1) != ' ';
                if (nextStartIsMidWord) {
                    // end - OVERLAP can land inside a word in non-periodic prose. Search
                    // forward, unbounded (not limited to the current chunk's end), for the
                    // next space and snap to just past it so the next chunk starts on a
                    // word boundary. A long unbroken token (URL, DOI, identifier) near the
                    // boundary could otherwise place the next space outside a bounded
                    // window, silently reproducing a mid-word start. If no space exists
                    // anywhere in the rest of the page's text, fall back to the unadjusted
                    // position; this last-resort fallback mirrors the single-long-token
                    // fallback on the `end` side above.
                    int forwardSpace = text.indexOf(' ', nextStart);
                    if (forwardSpace >= 0) {
                        nextStart = forwardSpace + 1;
                    }
                }
                start = nextStart;
            }
        }
        return drafts;
    }
}
