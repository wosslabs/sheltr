package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestDocumentServiceConfig {

    @Bean
    public PdfContentExtractor pdfContentExtractor() {
        return new PdfContentExtractor();
    }

    @Bean
    public IngestDocumentService ingestDocumentService(
            SourceConnector sourceConnector, PdfContentExtractor pdfContentExtractor,
            EmbeddingGenerator embeddingGenerator, SourceDocumentRepository sourceDocumentRepository,
            IngestionRunRepository ingestionRunRepository, KnowledgeDocumentRepository knowledgeDocumentRepository,
            ChunkRepository chunkRepository) {
        return new IngestDocumentService(sourceConnector, pdfContentExtractor, embeddingGenerator,
                sourceDocumentRepository, ingestionRunRepository, knowledgeDocumentRepository, chunkRepository);
    }
}
