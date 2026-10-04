package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.CorpusManifest;
import com.sheltr.ingestion.application.IngestCorpusService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class IngestCorpusRunner implements ApplicationRunner {

    private final IngestCorpusService ingestCorpusService;

    public IngestCorpusRunner(IngestCorpusService ingestCorpusService) {
        this.ingestCorpusService = ingestCorpusService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("ingest-corpus")) {
            return;
        }
        String path = args.containsOption("manifest")
                ? args.getOptionValues("manifest").get(0) : "../../corpus/manifest.yaml";
        CorpusManifest manifest;
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            manifest = CorpusManifest.parse(in);
        }
        IngestCorpusService.Summary summary = ingestCorpusService.ingest(manifest);
        System.out.println("Ingested: " + summary.ingested().size()
                + ", skipped (already in corpus): " + summary.skipped().size()
                + ", related hazards stored: " + summary.relatedHazards()
                + ", failed: " + summary.failed().size());
        summary.failed().forEach((what, why) -> System.out.println("FAILED " + what + ": " + why));
        System.exit(summary.failed().isEmpty() ? 0 : 1);
    }
}
