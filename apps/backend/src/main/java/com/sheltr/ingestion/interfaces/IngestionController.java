package com.sheltr.ingestion.interfaces;

import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.DisasterType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/ingestion/runs")
public class IngestionController {

    private final IngestDocumentService ingestDocumentService;
    private final IngestionRunRepository ingestionRunRepository;

    public IngestionController(IngestDocumentService ingestDocumentService,
                                IngestionRunRepository ingestionRunRepository) {
        this.ingestDocumentService = ingestDocumentService;
        this.ingestionRunRepository = ingestionRunRepository;
    }

    @PostMapping
    public ResponseEntity<IngestionRunDto> trigger(@Valid @RequestBody TriggerIngestionRequestDto request) {
        SourceType sourceType;
        SourceMetadata metadata;
        try {
            sourceType = SourceType.valueOf(request.sourceType());
            Set<DisasterType> hazards = request.hazards() == null ? Set.of()
                    : request.hazards().stream().map(DisasterType::valueOf).collect(Collectors.toSet());
            LocalDate publishedDate = request.publishedDate() == null ? null
                    : LocalDate.parse(request.publishedDate());
            metadata = new SourceMetadata(request.title(), request.sourceAgency(), hazards, publishedDate,
                    request.license());
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
        IngestionRun run = ingestDocumentService.ingest(request.sourceUri(), sourceType, metadata,
                request.checksum());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(run));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IngestionRunDto> get(@PathVariable UUID id) {
        return ingestionRunRepository.findById(new IngestionRunId(id))
                .map(run -> ResponseEntity.ok(toDto(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    private static IngestionRunDto toDto(IngestionRun run) {
        return new IngestionRunDto(run.id().value(), run.sourceDocumentId().value(), run.status().name(),
                run.startedAt(), run.finishedAt().orElse(null), run.errorMessage().orElse(null));
    }
}
