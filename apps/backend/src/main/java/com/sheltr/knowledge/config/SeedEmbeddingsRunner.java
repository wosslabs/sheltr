package com.sheltr.knowledge.config;

import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class SeedEmbeddingsRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final EmbeddingGenerator embeddingGenerator;

    public SeedEmbeddingsRunner(DataSource dataSource, EmbeddingGenerator embeddingGenerator) {
        this.dataSource = dataSource;
        this.embeddingGenerator = embeddingGenerator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains("seed-embeddings")) {
            return;
        }
        ChunkRepository chunkRepository = new JooqChunkRepository(dataSource);
        ChunkEmbeddingBackfillService service = new ChunkEmbeddingBackfillService(chunkRepository, embeddingGenerator);
        int count = service.backfillAll();
        System.out.println("Backfilled embeddings for " + count + " chunk(s).");
        System.exit(0);
    }
}
