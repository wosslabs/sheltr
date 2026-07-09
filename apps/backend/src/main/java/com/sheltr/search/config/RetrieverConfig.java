package com.sheltr.search.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.infrastructure.PgvectorSemanticSearchAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class RetrieverConfig {

    @Bean
    public Retriever retriever(DataSource dataSource, EmbeddingGenerator embeddingGenerator) {
        return new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator);
    }
}
