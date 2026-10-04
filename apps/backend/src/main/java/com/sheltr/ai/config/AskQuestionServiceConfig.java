package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.domain.ConversationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AskQuestionServiceConfig {

    @Bean
    public AskQuestionService askQuestionService(AnswerPipeline answerPipeline,
                                                 ConversationRepository conversationRepository) {
        return new AskQuestionService(answerPipeline, conversationRepository);
    }
}
