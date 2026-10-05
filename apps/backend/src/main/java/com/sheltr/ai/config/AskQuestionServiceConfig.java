package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.config.LimitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AskQuestionServiceConfig {

    @Bean
    public AskQuestionService askQuestionService(AnswerPipeline answerPipeline,
                                                 ConversationRepository conversationRepository,
                                                 LimitProperties limits) {
        return new AskQuestionService(answerPipeline, conversationRepository,
                limits.maxAsksPerDay(), Clock.systemUTC());
    }
}
