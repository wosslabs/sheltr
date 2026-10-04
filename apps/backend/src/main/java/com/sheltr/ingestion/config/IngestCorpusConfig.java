package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.IngestCorpusService;
import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.infrastructure.JooqCorpusCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class IngestCorpusConfig {

    @Bean
    public CorpusCatalog corpusCatalog(DataSource dataSource) {
        return new JooqCorpusCatalog(dataSource);
    }

    @Bean
    public IngestCorpusService ingestCorpusService(IngestDocumentService ingestDocumentService,
                                                   CorpusCatalog corpusCatalog) {
        return new IngestCorpusService(ingestDocumentService::ingest, corpusCatalog);
    }
}
