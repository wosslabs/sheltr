package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRelevanceJudgeTest {

    private static final SearchQuery QUERY = new SearchQuery("Is flood water safe to drink?");
    private static final List<RankedResult> PASSAGES = List.of(new RankedResult(
            new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water for one minute.", 0.8));

    @Test
    void parsesAnAnswerableVerdict() {
        RelevanceVerdict verdict = judge("ANSWERABLE: yes\nPROBABILITY: 0.85").judge(QUERY, PASSAGES);

        assertThat(verdict.answerable()).isTrue();
        assertThat(verdict.probability()).isEqualTo(0.85);
    }

    @Test
    void refusesWhenTheModelSaysNo() {
        assertThat(judge("ANSWERABLE: no\nPROBABILITY: 0.10").judge(QUERY, PASSAGES).answerable()).isFalse();
    }

    @Test
    void refusesWhenProbabilityIsBelowTheMinimumEvenIfTheModelSaysYes() {
        RelevanceVerdict verdict = judge("ANSWERABLE: yes\nPROBABILITY: 0.30").judge(QUERY, PASSAGES);

        assertThat(verdict.answerable()).isFalse();
        assertThat(verdict.probability()).isEqualTo(0.30);
    }

    @Test
    void failsClosedOnAnUnreadableReply() {
        assertThat(judge("Sure! These passages look helpful.").judge(QUERY, PASSAGES))
                .isEqualTo(RelevanceVerdict.REFUSE);
        assertThat(judge("ANSWERABLE: yes\nPROBABILITY: 7").judge(QUERY, PASSAGES))
                .isEqualTo(RelevanceVerdict.REFUSE);
    }

    @Test
    void failsClosedWhenTheModelThrows() {
        ModelRelevanceJudge judge = new ModelRelevanceJudge(prompt -> {
            throw new IllegalStateException("model unavailable");
        }, 0.5);

        assertThat(judge.judge(QUERY, PASSAGES)).isEqualTo(RelevanceVerdict.REFUSE);
    }

    @Test
    void refusesWithoutCallingTheModelWhenThereAreNoPassages() {
        ModelRelevanceJudge judge = new ModelRelevanceJudge(prompt -> {
            throw new AssertionError("model must not be called");
        }, 0.5);

        assertThat(judge.judge(QUERY, List.of())).isEqualTo(RelevanceVerdict.REFUSE);
    }

    private static ModelRelevanceJudge judge(String reply) {
        return new ModelRelevanceJudge(prompt -> reply, 0.5);
    }
}
