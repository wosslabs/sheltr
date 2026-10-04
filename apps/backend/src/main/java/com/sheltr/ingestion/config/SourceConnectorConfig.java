package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.infrastructure.HttpPdfConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourceConnectorConfig {

    @Bean
    public SourceConnector sourceConnector() {
        return new HttpPdfConnector();
    }
}
