package com.sheltr.ai.config;

import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.infrastructure.SpringAiAnswerGeneratorAdapter;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerGeneratorConfig {

    @Bean
    public AnswerGenerator answerGenerator(ChatModel chatModel, KnowledgeDocumentRepository documentRepository) {
        return new SpringAiAnswerGeneratorAdapter(chatModel, documentRepository);
    }
}
