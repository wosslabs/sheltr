package com.sheltr.search.domain;

public record SearchQuery(String text) {
    public SearchQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("SearchQuery text must not be blank");
        }
    }
}
