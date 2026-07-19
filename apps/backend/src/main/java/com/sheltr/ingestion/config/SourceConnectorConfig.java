package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.infrastructure.FemaHttpConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourceConnectorConfig {

    @Bean
    public SourceConnector sourceConnector() {
        return new FemaHttpConnector();
    }
}
