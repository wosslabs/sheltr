package com.sheltr.knowledge.interfaces;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentMetadataDto(UUID id, String title, String sourceAgency, String sourceUri,
                                   List<String> disasterTypes, LocalDate publishedDate, int version, String status) {
}
