package com.sheltr.ingestion.interfaces;

import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.SourceType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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
    public ResponseEntity<IngestionRunDto> trigger(@RequestBody TriggerIngestionRequestDto request) {
        SourceType sourceType = SourceType.valueOf(request.sourceType());
        IngestionRun run = ingestDocumentService.ingest(request.sourceUri(), sourceType);
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
