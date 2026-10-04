package com.sheltr.eval.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerJudgeTest {

    private static final List<String> PASSAGES = List.of("Boil water at a rolling boil for one minute.");

    @Test
    void faithfulnessIsTheShareOfSentencesThePassagesSupport() {
        AnswerJudge judge = new AnswerJudge(prompt ->
                prompt.contains("Sentence: Boil it for one minute.") ? "SUPPORTED" : "UNSUPPORTED");

        assertThat(judge.faithfulness("Boil it for one minute [1]. Then add salt [1].", PASSAGES)).isEqualTo(0.5);
        assertThat(judge.errors()).isZero();
    }

    @Test
    void stripsCitationMarkersBeforeJudgingASentence() {
        List<String> prompts = new ArrayList<>();
        new AnswerJudge(prompt -> {
            prompts.add(prompt);
            return "SUPPORTED";
        }).faithfulness("Boil it for one minute [1].", PASSAGES);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("Sentence: Boil it for one minute.").doesNotContain("[1].");
    }

    @Test
    void anUnreadableOrFailedReplyCountsAsUnsupportedAndIsCounted() {
        AnswerJudge unreadable = new AnswerJudge(prompt -> "Probably fine?");
        AnswerJudge failing = new AnswerJudge(prompt -> {
            throw new IllegalStateException("model unavailable");
        });

        assertThat(unreadable.faithfulness("Boil it.", PASSAGES)).isZero();
        assertThat(unreadable.errors()).isEqualTo(1);
        assertThat(failing.faithfulness("Boil it.", PASSAGES)).isZero();
        assertThat(failing.errors()).isEqualTo(1);
    }

    @Test
    void anEmptyAnswerHasZeroFaithfulness() {
        assertThat(new AnswerJudge(prompt -> "SUPPORTED").faithfulness("  ", PASSAGES)).isZero();
    }

    @Test
    void keyFactCoverageIsTheShareOfFactsTheAnswerStates() {
        AnswerJudge judge = new AnswerJudge(prompt -> prompt.contains("Fact: one minute") ? "YES" : "NO");

        assertThat(judge.keyFactCoverage("Boil it for one minute.", List.of("one minute", "use bleach")))
                .isEqualTo(0.5);
    }

    @Test
    void unsupportedIsNotMistakenForSupported() {
        assertThat(new AnswerJudge(prompt -> "UNSUPPORTED").faithfulness("Boil it.", PASSAGES)).isZero();
    }

    @Test
    void aReplyWithMarkdownOrQuotesAroundTheWordIsStillRead() {
        assertThat(new AnswerJudge(prompt -> "**SUPPORTED**").faithfulness("Boil it.", PASSAGES)).isEqualTo(1.0);
        assertThat(new AnswerJudge(prompt -> "\"YES\"").keyFactCoverage("Boil it.", List.of("boil"))).isEqualTo(1.0);
    }

    @Test
    void unsupportedIsReadAsUnsupportedWhetherOrNotItIsQuoted() {
        assertThat(new AnswerJudge(prompt -> "UNSUPPORTED").faithfulness("Boil it.", PASSAGES)).isZero();
        assertThat(new AnswerJudge(prompt -> "**UNSUPPORTED**").faithfulness("Boil it.", PASSAGES)).isZero();
    }

    @Test
    void theAnswerWordMustBeTheWholeFirstWord() {
        AnswerJudge judge = new AnswerJudge(prompt -> "NOTE: YES");

        assertThat(judge.keyFactCoverage("Boil it.", List.of("boil"))).isZero();
        assertThat(judge.errors()).isEqualTo(1);
    }

    @Test
    void noIsReadAsNoEvenThoughNoteStartsWithIt() {
        AnswerJudge judge = new AnswerJudge(prompt -> "NO");

        assertThat(judge.keyFactCoverage("Boil it.", List.of("boil"))).isZero();
        assertThat(judge.errors()).isZero();
    }

    @Test
    void aNoteReplyIsUnreadableAndNotANo() {
        AnswerJudge judge = new AnswerJudge(prompt -> "NOTE: NO");

        assertThat(judge.keyFactCoverage("Boil it.", List.of("boil"))).isZero();
        assertThat(judge.errors()).isEqualTo(1);
    }

    @Test
    void citationListsAndRangesAreRemovedBeforeJudging() {
        List<String> prompts = new ArrayList<>();
        new AnswerJudge(prompt -> {
            prompts.add(prompt);
            return "SUPPORTED";
        }).faithfulness("Boil it for one minute [1, 2]. Then add salt [1-3]. Stir [12].", PASSAGES);

        assertThat(prompts).hasSize(3);
        assertThat(prompts.get(0)).contains("Sentence: Boil it for one minute.").doesNotContain("[");
        assertThat(prompts.get(1)).contains("Sentence: Then add salt.").doesNotContain("[");
        assertThat(prompts.get(2)).contains("Sentence: Stir.").doesNotContain("[");
    }
}
