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
                }
                String chunkText = text.substring(start, end).trim();
                if (!chunkText.isBlank()) {
                    drafts.add(new ChunkDraft(chunkText, new PageNumber(page.pageNumber())));
                }
                if (end >= text.length()) {
                    break;
                }
                start = Math.max(end - OVERLAP, start + 1);
            }
        }
        return drafts;
    }
}
