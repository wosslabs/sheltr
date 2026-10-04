package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerPipelineTest {

    private static final SearchQuery QUERY = new SearchQuery("Is flood water safe to drink?");
    private static final Message ANSWER = new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
            new MessageContent("Boil it [1]."), List.of(), AnswerConfidence.fromSimilarity(0.9), List.of());

    @Test
    void asksForTwentyCandidatesAndPassesFiveToTheGenerator() {
        List<Integer> requested = new ArrayList<>();
        List<Integer> generatedWith = new ArrayList<>();
        Retriever retriever = (query, topK) -> {
            requested.add(topK);
            return passages(20, 0.9);
        };
        AnswerGenerator generator = (query, results, verdict) -> {
            generatedWith.add(results.size());
            return new AnswerGenerated(ANSWER);
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(0.75), generator).answer(QUERY);

        assertThat(requested).containsExactly(20);
        assertThat(generatedWith).containsExactly(5);
        assertThat(result.outcome()).isEqualTo(new AnswerGenerated(ANSWER));
        assertThat(result.verdict().answerable()).isTrue();
    }

    @Test
    void refusesWithoutGeneratingWhenTheJudgeSaysNotAnswerable() {
        AnswerGenerator generator = (query, results, verdict) -> {
            throw new AssertionError("generator must not be called");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> passages(3, 0.2),
                new NoOpReranker(), new ThresholdRelevanceJudge(0.75), generator).answer(QUERY);

        assertThat(result.outcome()).isInstanceOf(NoAnswerAvailable.class);
        assertThat(result.passages()).hasSize(3);
    }

    @Test
    void refusesWhenNothingIsRetrieved() {
        RelevanceJudge judge = (query, passages) -> {
            throw new AssertionError("judge must not be called with no passages");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> List.of(),
                new NoOpReranker(), judge, (query, results, verdict) -> new AnswerGenerated(ANSWER)).answer(QUERY);

        assertThat(result.outcome()).isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void refusesWhenTheJudgeThrows() {
        RelevanceJudge judge = (query, passages) -> {
            throw new IllegalStateException("judge unavailable");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> passages(3, 0.9),
                new NoOpReranker(), judge, (query, results, verdict) -> new AnswerGenerated(ANSWER)).answer(QUERY);

        assertThat(result.outcome()).isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void keepsRetrievalOrderWhenTheRerankerThrows() {
        Reranker reranker = (query, candidates, topK) -> {
            throw new IllegalStateException("reranker unavailable");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> passages(8, 0.9),
                reranker, new ThresholdRelevanceJudge(0.75),
                (query, results, verdict) -> new AnswerGenerated(ANSWER)).answer(QUERY);

        assertThat(result.passages()).hasSize(5);
        assertThat(result.outcome()).isEqualTo(new AnswerGenerated(ANSWER));
    }

    private static List<RankedResult> passages(int count, double score) {
        List<RankedResult> passages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            passages.add(new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                    "passage " + i, score));
        }
        return passages;
    }
}
