package com.sheltr.ai.config;

import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.search.domain.Retriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AskQuestionServiceConfig {

    @Bean
    public AskQuestionService askQuestionService(
            Retriever retriever, AnswerGenerator answerGenerator, ConversationRepository conversationRepository,
            @Value("${sheltr.search.relevance-threshold}") double relevanceThreshold) {
        return new AskQuestionService(retriever, answerGenerator, conversationRepository, relevanceThreshold);
    }
}
