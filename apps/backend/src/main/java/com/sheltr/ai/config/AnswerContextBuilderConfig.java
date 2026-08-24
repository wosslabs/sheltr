package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerContextBuilderConfig {

    @Bean
    public AnswerContextBuilder answerContextBuilder(KnowledgeDocumentRepository documentRepository) {
        return new AnswerContextBuilder(documentRepository);
    }
}
