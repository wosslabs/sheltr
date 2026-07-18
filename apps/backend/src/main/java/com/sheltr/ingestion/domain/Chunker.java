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
                if (text.charAt(nextStart) != ' ') {
                    // end - OVERLAP can land inside a word in non-periodic prose. Search
                    // forward (bounded by the current chunk's end, not unbounded) for the
                    // next space and snap to just past it so the next chunk starts on a
                    // word boundary. If no space is found in that bounded range, fall back
                    // to the unadjusted position rather than searching without bound.
                    int forwardSpace = text.indexOf(' ', nextStart);
                    if (forwardSpace >= 0 && forwardSpace < end) {
                        nextStart = forwardSpace + 1;
                    }
                }
                start = nextStart;
            }
        }
        return drafts;
    }
}
