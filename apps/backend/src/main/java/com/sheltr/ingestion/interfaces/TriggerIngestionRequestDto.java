package com.sheltr.ingestion.interfaces;

import java.util.List;

public record TriggerIngestionRequestDto(String sourceUri, String sourceType, String title, String sourceAgency,
                                         List<String> hazards, String publishedDate, String license,
                                         String checksum) {
}
