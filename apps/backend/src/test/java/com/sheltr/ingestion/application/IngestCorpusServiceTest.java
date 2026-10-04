package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestCorpusServiceTest {

    private static final String OLD = "https://example.org/old.pdf";
    private static final String NEW = "https://example.org/new.pdf";
    private static final String BAD = "https://example.org/bad.pdf";

    @Test
    void ingestsNewDocumentsSkipsExistingOnesAndReportsFailures() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.active.put(OLD, new DocumentId(UUID.randomUUID()));
        List<String> ingestedUris = new ArrayList<>();
        IngestCorpusService service = new IngestCorpusService((uri, type, metadata, checksum) -> {
            ingestedUris.add(uri + "#" + checksum);
            IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()),
                    new DocumentId(UUID.randomUUID()));
            if (uri.equals(BAD)) {
                run.markFailed("Checksum mismatch");
            } else {
                run.markSucceeded();
                catalog.active.put(uri, new DocumentId(UUID.randomUUID()));
            }
            return run;
        }, catalog);

        IngestCorpusService.Summary summary = service.ingest(new CorpusManifest(
                List.of(document(OLD), document(NEW), document(BAD)), List.of()));

        assertThat(ingestedUris).containsExactly(NEW + "#sum", BAD + "#sum");
        assertThat(summary.ingested()).containsExactly(NEW);
        assertThat(summary.skipped()).containsExactly(OLD);
        assertThat(summary.failed()).containsEntry(BAD, "Checksum mismatch");
    }

    @Test
    void storesRelatedHazardsOnlyWhenTheirSourceDocumentIsInTheCorpus() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.active.put(OLD, new DocumentId(UUID.randomUUID()));
        IngestCorpusService service = new IngestCorpusService((uri, type, metadata, checksum) -> {
            IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()),
                    new DocumentId(UUID.randomUUID()));
            run.markFailed("download failed");
            return run;
        }, catalog);

        IngestCorpusService.Summary summary = service.ingest(new CorpusManifest(
                List.of(document(OLD), document(BAD)),
                List.of(new CorpusManifest.RelatedHazard(DisasterType.EARTHQUAKE, DisasterType.LANDSLIDE, OLD, null),
                        new CorpusManifest.RelatedHazard(DisasterType.FLOOD, DisasterType.LANDSLIDE, BAD, null))));

        assertThat(catalog.related).containsExactly("EARTHQUAKE->LANDSLIDE");
        assertThat(summary.relatedHazards()).isEqualTo(1);
        assertThat(summary.failed()).containsKey("related hazard FLOOD->LANDSLIDE");
    }

    private static CorpusManifest.Document document(String uri) {
        return new CorpusManifest.Document(uri, new SourceMetadata("Title", "Agency",
                Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15), "test-fixture-licence"), "sum");
    }

    private static class FakeCatalog implements CorpusCatalog {
        final Map<String, DocumentId> active = new HashMap<>();
        final List<String> related = new ArrayList<>();

        @Override
        public Optional<DocumentId> findActiveDocumentId(String sourceUri) {
            return Optional.ofNullable(active.get(sourceUri));
        }

        @Override
        public void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source,
                                        String note) {
            related.add(hazard + "->" + relatedHazard);
        }
    }
}
