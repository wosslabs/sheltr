package com.sheltr.eval.domain;

/** A passage is identified by a verbatim phrase, not a chunk ID, so it survives re-ingestion. */
public record ExpectedPassage(String sourceUri, String contains) {
}
