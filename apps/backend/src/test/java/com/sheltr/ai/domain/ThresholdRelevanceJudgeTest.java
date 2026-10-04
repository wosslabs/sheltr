package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdRelevanceJudgeTest {

    private static final SearchQuery QUERY = new SearchQuery("question");
    private final ThresholdRelevanceJudge judge = new ThresholdRelevanceJudge(0.75);

    @Test
    void answerableWhenTopScoreMeetsThreshold() {
        RelevanceVerdict verdict = judge.judge(QUERY, List.of(passage(0.80), passage(0.10)));

        assertThat(verdict.answerable()).isTrue();
        assertThat(verdict.probability()).isEqualTo(0.80);
    }

    @Test
    void usesTheBestScoreEvenWhenItIsNotFirst() {
        assertThat(judge.judge(QUERY, List.of(passage(0.10), passage(0.80))).answerable()).isTrue();
    }

    @Test
    void notAnswerableBelowThresholdOrWithNoPassages() {
        assertThat(judge.judge(QUERY, List.of(passage(0.70))).answerable()).isFalse();
        assertThat(judge.judge(QUERY, List.of())).isEqualTo(RelevanceVerdict.REFUSE);
    }

    private static RankedResult passage(double score) {
        return new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", score);
    }
}
