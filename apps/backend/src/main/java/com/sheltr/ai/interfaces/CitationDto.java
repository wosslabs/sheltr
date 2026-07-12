package com.sheltr.ai.interfaces;

import java.util.UUID;

public record CitationDto(UUID documentId, UUID chunkId, String sourceTitle, String sourceUrl, Integer pageNumber) {
}
