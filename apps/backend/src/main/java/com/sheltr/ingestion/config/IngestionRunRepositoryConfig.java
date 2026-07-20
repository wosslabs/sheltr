package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.infrastructure.JooqIngestionRunRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class IngestionRunRepositoryConfig {

    @Bean
    public IngestionRunRepository ingestionRunRepository(DataSource dataSource) {
        return new JooqIngestionRunRepository(dataSource);
    }
}
