package com.sheltr.eval.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MetricsTest {

    private static final String URI = "https://example.org/flood.pdf";
    private static final ExpectedPassage BOIL = new ExpectedPassage(URI, "rolling boil for one minute");
    private static final ExpectedPassage BLEACH = new ExpectedPassage(URI, "unscented bleach");

    @Test
    void matchesIgnoringCaseAndLineBreaksButNotAcrossDocuments() {
        assertThat(Metrics.matches(BOIL, new RetrievedPassage(URI, "Bring to a ROLLING\n boil   for one minute.")))
                .isTrue();
        assertThat(Metrics.matches(BOIL,
                new RetrievedPassage("https://example.org/other.pdf", "rolling boil for one minute"))).isFalse();
    }

    @Test
    void anAnswerableQuestionIsHitWhenAnyExpectedPassageIsInTheTopK() {
        EvalQuestion question = question("q1", EvalKind.ANSWERABLE, List.of(BOIL, BLEACH));
        List<RetrievedPassage> passages = List.of(passage("unrelated"), passage("use unscented bleach"));

        assertThat(Metrics.hit(question, passages, 5)).isTrue();
        assertThat(Metrics.hit(question, passages, 1)).isFalse();
        assertThat(Metrics.firstHitRank(question, passages)).isEqualTo(2);
    }

    @Test
    void aMultiStepQuestionIsHitOnlyWhenEveryExpectedPassageIsInTheTopK() {
        EvalQuestion question = question("q1", EvalKind.MULTI_STEP, List.of(BOIL, BLEACH));

        assertThat(Metrics.hit(question, List.of(passage("use unscented bleach")), 5)).isFalse();
        assertThat(Metrics.hit(question,
                List.of(passage("use unscented bleach"), passage("rolling boil for one minute")), 5)).isTrue();
    }

    @Test
    void recallAndReciprocalRankCoverOnlyQuestionsThatShouldBeAnswered() {
        List<Metrics.Scored> scored = List.of(
                scored(question("hit-first", EvalKind.ANSWERABLE, List.of(BOIL)),
                        result("hit-first", false, List.of(passage("rolling boil for one minute")))),
                scored(question("miss", EvalKind.ANSWERABLE, List.of(BOIL)),
                        result("miss", false, List.of(passage("unrelated")))),
                scored(question("off", EvalKind.OFF_TOPIC, List.of()), result("off", true, List.of())));

        assertThat(Metrics.recallAtK(scored, 5)).isEqualTo(0.5);
        assertThat(Metrics.meanReciprocalRank(scored)).isEqualTo(0.5);
    }

    @Test
    void falseAcceptsAreMustRefuseQuestionsThatGotAnAnswer() {
        List<Metrics.Scored> scored = List.of(
                scored(question("off", EvalKind.OFF_TOPIC, List.of()), result("off", false, List.of())),
                scored(question("uncovered", EvalKind.UNCOVERED, List.of()), result("uncovered", true, List.of())),
                scored(question("ok", EvalKind.ANSWERABLE, List.of(BOIL)), result("ok", true, List.of())));

        assertThat(Metrics.falseAcceptRate(scored)).isEqualTo(0.5);
        assertThat(Metrics.falseRefuseRate(scored)).isEqualTo(1.0);
    }

    @Test
    void anErroredResultCountsAsRefusedEvenWhenTheFlagIsFalse() {
        EvalResult errored = new EvalResult("off", List.of(), false, null, null, List.of(), 100, "llm timeout");
        List<Metrics.Scored> scored = List.of(
                scored(question("off", EvalKind.OFF_TOPIC, List.of()), errored));

        assertThat(errored.refused()).isTrue();
        assertThat(Metrics.falseAcceptRate(scored)).isEqualTo(0.0);
    }

    @Test
    void meanReciprocalRankForAHitAtRankTwoAndAtRankFour() {
        EvalQuestion question = question("q1", EvalKind.ANSWERABLE, List.of(BOIL));

        assertThat(Metrics.meanReciprocalRank(List.of(scored(question,
                result("q1", false, List.of(passage("unrelated"), passage("rolling boil for one minute")))))))
                .isEqualTo(0.5);
        assertThat(Metrics.meanReciprocalRank(List.of(scored(question,
                result("q1", false, List.of(passage("a"), passage("b"), passage("c"),
                        passage("rolling boil for one minute")))))))
                .isEqualTo(0.25);
    }

    @Test
    void ratesAreNotANumberWhenNoQuestionApplies() {
        assertThat(Metrics.falseAcceptRate(List.of())).isNaN();
        assertThat(Metrics.recallAtK(List.of(), 5)).isNaN();
    }

    @Test
    void calibrationErrorIsTheGapBetweenStatedConfidenceAndObservedAccuracy() {
        // Two answers at 0.9 confidence, one right: accuracy 0.5, gap 0.4.
        assertThat(Metrics.calibrationError(List.of(new double[]{0.9, 1}, new double[]{0.9, 0}), 5))
                .isCloseTo(0.4, within(1e-9));
        // Bin 4 (0.8-1.0): confidence 0.9, accuracy 0.5, gap 0.4, weight 2/3.
        // Bin 0 (0.0-0.2): confidence 0.1, accuracy 0.0, gap 0.1, weight 1/3.
        assertThat(Metrics.calibrationError(
                List.of(new double[]{0.9, 1}, new double[]{0.9, 0}, new double[]{0.1, 0}), 5))
                .isCloseTo((2.0 / 3) * 0.4 + (1.0 / 3) * 0.1, within(1e-9));
    }

    @Test
    void calibrationErrorRejectsConfidencesOutsideZeroToOneOrNaN() {
        assertThatThrownBy(() -> Metrics.calibrationError(List.of(new double[]{Double.NaN, 1}), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Metrics.calibrationError(List.of(new double[]{1.5, 1}), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Metrics.calibrationError(List.of(new double[]{-0.1, 0}), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentilePicksTheNearestRank() {
        assertThat(Metrics.percentile(List.of(10L, 20L, 30L, 40L), 0.5)).isEqualTo(20L);
        assertThat(Metrics.percentile(List.of(10L, 20L, 30L, 40L), 0.95)).isEqualTo(40L);
        assertThat(Metrics.percentile(List.of(), 0.5)).isZero();
    }

    private static EvalQuestion question(String id, EvalKind kind, List<ExpectedPassage> expected) {
        return new EvalQuestion(id, "question", kind, Set.of(), expected, List.of(), "tune");
    }

    private static RetrievedPassage passage(String content) {
        return new RetrievedPassage(URI, content);
    }

    private static EvalResult result(String id, boolean refused, List<RetrievedPassage> passages) {
        return new EvalResult(id, passages, refused, refused ? null : "answer", 0.8, List.of(), 100, null);
    }

    private static Metrics.Scored scored(EvalQuestion question, EvalResult result) {
        return new Metrics.Scored(question, result);
    }
}
