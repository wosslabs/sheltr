package com.sheltr.ai.config;

import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.infrastructure.JooqConversationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class ConversationRepositoryConfig {

    @Bean
    public ConversationRepository conversationRepository(DataSource dataSource) {
        return new JooqConversationRepository(dataSource);
    }
}
