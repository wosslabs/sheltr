package com.sheltr.ingestion.domain;

public final class TextCleaner {

    private TextCleaner() {
    }

    public static String clean(String rawText) {
        String collapsed = rawText.replaceAll("[ \\t]+", " ");
        collapsed = collapsed.replaceAll("\\n{3,}", "\n\n");
        return collapsed.trim();
    }
}
