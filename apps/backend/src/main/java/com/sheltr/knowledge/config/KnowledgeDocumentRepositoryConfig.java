package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.knowledge.infrastructure.JooqKnowledgeDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class KnowledgeDocumentRepositoryConfig {

    @Bean
    public KnowledgeDocumentRepository knowledgeDocumentRepository(DataSource dataSource) {
        return new JooqKnowledgeDocumentRepository(dataSource);
    }
}
