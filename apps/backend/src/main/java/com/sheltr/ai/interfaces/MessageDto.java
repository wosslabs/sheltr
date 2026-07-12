package com.sheltr.ai.interfaces;

import java.util.List;
import java.util.UUID;

public record MessageDto(UUID id, String role, String content, Double confidenceScore,
                          String confidenceLevel, List<CitationDto> citations) {
}
