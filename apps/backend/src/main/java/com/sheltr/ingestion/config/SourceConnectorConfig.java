package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourcePolicy;
import com.sheltr.ingestion.infrastructure.HtmlPageConnector;
import com.sheltr.ingestion.infrastructure.HttpPdfConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourceConnectorConfig {

    @Bean
    public HttpPdfConnector httpPdfConnector(IngestionProperties properties) {
        return new HttpPdfConnector(properties.connectTimeout(), properties.requestTimeout());
    }

    @Bean
    public HtmlPageConnector htmlPageConnector(IngestionProperties properties, SourcePolicy sourcePolicy) {
        return new HtmlPageConnector(sourcePolicy, properties.connectTimeout(), properties.requestTimeout());
    }
}
