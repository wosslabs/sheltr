package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.ingestion.domain.StoredDocument;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class IngestCorpusService {

    @FunctionalInterface
    public interface DocumentIngestor {
        IngestionRun ingest(String sourceUri, SourceType sourceType, SourceMetadata metadata, String checksum);
    }

    public record Summary(List<String> ingested, List<String> skipped, Map<String, String> failed,
                          int relatedHazards) {
    }

    private final DocumentIngestor ingestor;
    private final CorpusCatalog catalog;

    public IngestCorpusService(DocumentIngestor ingestor, CorpusCatalog catalog) {
        this.ingestor = ingestor;
        this.catalog = catalog;
    }

    public Summary ingest(CorpusManifest manifest) {
        List<String> ingested = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (CorpusManifest.Document document : manifest.documents()) {
            Optional<StoredDocument> existing = catalog.findActiveDocument(document.sourceUri());
            if (existing.isPresent()) {
                String stored = existing.get().checksum();
                if (stored == null || !document.checksum().equalsIgnoreCase(stored)) {
                    failed.put(document.sourceUri(), "checksum mismatch: approved " + document.checksum()
                            + " but stored " + (stored == null ? "none" : stored) + "; re-approval needed");
                } else {
                    skipped.add(document.sourceUri());
                }
                continue;
            }
            IngestionRun run = ingestor.ingest(document.sourceUri(), SourceType.PDF, document.metadata(),
                    document.checksum());
            if (run.status() == RunStatus.SUCCEEDED) {
                ingested.add(document.sourceUri());
            } else {
                failed.put(document.sourceUri(), run.errorMessage().orElse("unknown error"));
            }
        }

        int relatedHazards = 0;
        for (CorpusManifest.RelatedHazard related : manifest.relatedHazards()) {
            Optional<DocumentId> source = catalog.findActiveDocumentId(related.sourceUri());
            if (source.isEmpty()) {
                failed.put("related hazard " + related.hazard() + "->" + related.relatedHazard(),
                        "source document " + related.sourceUri() + " is not in the corpus");
                continue;
            }
            catalog.upsertRelatedHazard(related.hazard(), related.relatedHazard(), source.get(), related.note());
            relatedHazards++;
        }
        return new Summary(ingested, skipped, failed, relatedHazards);
    }
}
