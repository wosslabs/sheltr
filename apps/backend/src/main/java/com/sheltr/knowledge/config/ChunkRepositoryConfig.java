package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class ChunkRepositoryConfig {

    @Bean
    public ChunkRepository chunkRepository(DataSource dataSource) {
        return new JooqChunkRepository(dataSource);
    }
}
