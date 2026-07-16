package com.sheltr.ingestion.domain;

public record PageText(int pageNumber, String text) {
    public PageText {
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be >= 1");
        if (text == null) throw new IllegalArgumentException("text must not be null");
    }
}
