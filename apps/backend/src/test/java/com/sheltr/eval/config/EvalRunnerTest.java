package com.sheltr.eval.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void refusesWhenTheJudgeProfileIsTheProfileThatProducedTheRun() {
        assertThat(EvalRunner.judgeProfileConflict("gemini", "gemini"))
                .contains("both are gemini")
                .contains("SPRING_PROFILES_ACTIVE=local");
    }

    @Test
    void comparesProfileListsIgnoringOrderAndSpaces() {
        assertThat(EvalRunner.judgeProfileConflict("dev,gemini", "gemini, dev")).contains("both are dev,gemini");
    }

    @Test
    void allowsADifferentJudgeProfile() {
        assertThat(EvalRunner.judgeProfileConflict("ollama", "gemini")).isNull();
        assertThat(EvalRunner.judgeProfileConflict("gemini", "gemini,dev")).isNull();
    }

    @Test
    void suggestsGeminiWhenTheRunWasProducedUnderAnotherProfile() {
        assertThat(EvalRunner.judgeProfileConflict("local", "local"))
                .contains("both are local")
                .contains("SPRING_PROFILES_ACTIVE=gemini");
    }

    @Test
    void allowsRunsWithNoProfileOrNoRecordedProfile() {
        assertThat(EvalRunner.judgeProfileConflict("", "")).isNull();
        assertThat(EvalRunner.judgeProfileConflict(null, "gemini")).isNull();
    }

    @Test
    void refusesToOverwriteAnExistingSpotcheckFileWithoutForce() {
        assertThat(EvalRunner.spotcheckWriteDecision(true, false)).isEqualTo(EvalRunner.SpotcheckWrite.REFUSE);
    }

    @Test
    void overwritesAnExistingSpotcheckFileWhenForced() {
        assertThat(EvalRunner.spotcheckWriteDecision(true, true)).isEqualTo(EvalRunner.SpotcheckWrite.OVERWRITE);
    }

    @Test
    void writesSpotcheckFileWhenNoneExists() {
        assertThat(EvalRunner.spotcheckWriteDecision(false, false)).isEqualTo(EvalRunner.SpotcheckWrite.WRITE);
        assertThat(EvalRunner.spotcheckWriteDecision(false, true)).isEqualTo(EvalRunner.SpotcheckWrite.WRITE);
    }
}
