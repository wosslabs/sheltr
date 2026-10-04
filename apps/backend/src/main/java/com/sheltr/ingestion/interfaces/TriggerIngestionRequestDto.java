package com.sheltr.ingestion.interfaces;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record TriggerIngestionRequestDto(@NotBlank String sourceUri, String sourceType, String title, String sourceAgency,
                                         List<String> hazards, String publishedDate, String license,
                                         String checksum) {
}
