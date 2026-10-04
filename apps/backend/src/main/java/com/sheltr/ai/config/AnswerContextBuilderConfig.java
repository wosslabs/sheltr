package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.ConfidenceBands;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerContextBuilderConfig {

    @Bean
    public ConfidenceBands confidenceBands(@Value("${sheltr.confidence.high}") double high,
                                           @Value("${sheltr.confidence.medium}") double medium) {
        return new ConfidenceBands(high, medium);
    }

    @Bean
    public AnswerContextBuilder answerContextBuilder(KnowledgeDocumentRepository documentRepository,
                                                     ConfidenceBands confidenceBands) {
        return new AnswerContextBuilder(documentRepository, confidenceBands);
    }
}
