package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourcePolicy;
import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.ingestion.infrastructure.HtmlPageConnector;
import com.sheltr.ingestion.infrastructure.HttpPdfConnector;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class IngestDocumentServiceConfig {

    @Bean
    public PdfContentExtractor pdfContentExtractor() {
        return new PdfContentExtractor();
    }

    @Bean
    public SourcePolicy sourcePolicy(IngestionProperties properties) {
        return new HostAllowlistSourcePolicy(properties);
    }

    @Bean
    public IngestDocumentService ingestDocumentService(
            HttpPdfConnector httpPdfConnector, HtmlPageConnector htmlPageConnector,
            PdfContentExtractor pdfContentExtractor,
            EmbeddingGenerator embeddingGenerator, SourceDocumentRepository sourceDocumentRepository,
            IngestionRunRepository ingestionRunRepository, KnowledgeDocumentRepository knowledgeDocumentRepository,
            ChunkRepository chunkRepository, SourcePolicy sourcePolicy) {
        Map<SourceType, SourceConnector> connectors = Map.of(
                SourceType.PDF, httpPdfConnector,
                SourceType.HTML, htmlPageConnector);
        return new IngestDocumentService(connectors, pdfContentExtractor, embeddingGenerator,
                sourceDocumentRepository, ingestionRunRepository, knowledgeDocumentRepository, chunkRepository,
                sourcePolicy);
    }
}
