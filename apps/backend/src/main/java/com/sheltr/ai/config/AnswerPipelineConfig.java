package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.domain.RelevanceJudge;
import com.sheltr.ai.domain.ThresholdRelevanceJudge;
import com.sheltr.ai.infrastructure.ModelRelevanceJudge;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.Retriever;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerPipelineConfig {

    @Bean
    public RelevanceJudge relevanceJudge(ChatModel chatModel,
                                         @Value("${sheltr.judge.type}") String type,
                                         @Value("${sheltr.judge.min-probability}") double minProbability,
                                         @Value("${sheltr.search.relevance-threshold}") double threshold) {
        return switch (type) {
            case "threshold" -> new ThresholdRelevanceJudge(threshold);
            case "model" -> new ModelRelevanceJudge(chatModel::call, minProbability);
            default -> throw new IllegalArgumentException(
                    "sheltr.judge.type must be 'threshold' or 'model', got '" + type + "'");
        };
    }

    @Bean
    public AnswerPipeline answerPipeline(Retriever retriever, Reranker reranker, RelevanceJudge relevanceJudge,
                                         AnswerGenerator answerGenerator) {
        return new AnswerPipeline(retriever, reranker, relevanceJudge, answerGenerator);
    }
}
