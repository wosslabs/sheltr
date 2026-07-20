package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.infrastructure.JooqSourceDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class SourceDocumentRepositoryConfig {

    @Bean
    public SourceDocumentRepository sourceDocumentRepository(DataSource dataSource) {
        return new JooqSourceDocumentRepository(dataSource);
    }
}
