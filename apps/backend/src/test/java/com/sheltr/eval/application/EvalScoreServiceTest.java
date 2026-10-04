package com.sheltr.eval.application;

import com.sheltr.eval.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalScoreServiceTest {

    private static final String URI = "https://example.org/flood.pdf";
    private static final RetrievedPassage BOIL = new RetrievedPassage(URI, "rolling boil for one minute");

    private final List<EvalQuestion> questions = List.of(
            new EvalQuestion("good", "q", EvalKind.ANSWERABLE, Set.of(),
                    List.of(new ExpectedPassage(URI, "rolling boil")), List.of("one minute"), "tune"),
            new EvalQuestion("refused", "q", EvalKind.ANSWERABLE, Set.of(),
                    List.of(new ExpectedPassage(URI, "rolling boil")), List.of("one minute"), "tune"),
            new EvalQuestion("off", "q", EvalKind.OFF_TOPIC, Set.of(), List.of(), List.of(), "tune"),
            new EvalQuestion("held", "q", EvalKind.UNCOVERED, Set.of(), List.of(), List.of(), "heldout"));

    private final EvalRun run = new EvalRun("baseline", "2026-10-04T00:00:00Z", Map.of("mode", "vector"), List.of(
            new EvalResult("good", List.of(BOIL), false, "Boil for one minute [1].", 0.9, List.of(BOIL), 100, null),
            new EvalResult("refused", List.of(BOIL), true, null, 0.3, List.of(), 200, null),
            new EvalResult("off", List.of(), false, "Bake at 200 degrees [1].", 0.8, List.of(BOIL), 300, null),
            new EvalResult("held", List.of(), true, null, 0.1, List.of(), 400, null)));

    // Supports and covers everything except the off-topic answer about baking.
    private final AnswerJudge judge = new AnswerJudge(prompt ->
            prompt.contains("Bake") ? "UNSUPPORTED" : prompt.contains("Fact:") ? "YES" : "SUPPORTED");

    @Test
    void scoresEachSplitSeparately() {
        EvalReport report = new EvalScoreService(judge).score(questions, run).report();

        SplitScores tune = report.splits().get("tune");
        assertThat(tune.questions()).isEqualTo(3);
        assertThat(tune.recallAt5()).isEqualTo(1.0);
        assertThat(tune.falseAcceptRate()).isEqualTo(1.0);
        assertThat(tune.falseRefuseRate()).isEqualTo(0.5);
        assertThat(tune.faithfulness()).isEqualTo(0.5);
        assertThat(tune.keyFactCoverage()).isEqualTo(1.0);
        assertThat(tune.medianLatencyMs()).isEqualTo(200);

        SplitScores heldout = report.splits().get("heldout");
        assertThat(heldout.questions()).isEqualTo(1);
        assertThat(heldout.falseAcceptRate()).isZero();
        assertThat(report.config()).isEqualTo("baseline");
    }

    @Test
    void buildsAConfidenceTableFromTheTuneSplit() {
        List<ConfidenceRow> table = new EvalScoreService(judge).score(questions, run).report()
                .tuneConfidenceTable();

        // Two tune answers: "good" at 0.9 (correct) and "off" at 0.8 (wrong).
        ConfidenceRow atHalf = table.stream().filter(row -> row.threshold() == 0.50).findFirst().orElseThrow();
        ConfidenceRow atNinety = table.stream().filter(row -> row.threshold() == 0.90).findFirst().orElseThrow();
        assertThat(atHalf.answers()).isEqualTo(2);
        assertThat(atHalf.accuracy()).isEqualTo(0.5);
        assertThat(atNinety.answers()).isEqualTo(1);
        assertThat(atNinety.accuracy()).isEqualTo(1.0);
    }

    @Test
    void spotcheckListsAnsweredQuestionsWithTheJudgesVerdictAndABlankHumanColumn() {
        List<SpotcheckItem> spotcheck = new EvalScoreService(judge).score(questions, run).spotcheck();

        assertThat(spotcheck).extracting(SpotcheckItem::id).containsExactlyInAnyOrder("good", "off");
        assertThat(spotcheck).allSatisfy(item -> assertThat(item.humanFaithful()).isNull());
        assertThat(spotcheck.stream().filter(item -> item.id().equals("good")).findFirst().orElseThrow()
                .judgeFaithful()).isTrue();
    }

    @Test
    void rejectsARunThatIsMissingAQuestion() {
        EvalRun partial = new EvalRun("baseline", "t", Map.of(), run.results().subList(0, 2));

        assertThatThrownBy(() -> new EvalScoreService(judge).score(questions, partial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("off");
    }

    @Test
    void agreementIsTheShareOfItemsWhereJudgeAndHumanMatch() {
        List<SpotcheckItem> items = List.of(
                new SpotcheckItem("a", "q", "a", List.of(), true, true),
                new SpotcheckItem("b", "q", "a", List.of(), true, false),
                new SpotcheckItem("c", "q", "a", List.of(), false, false),
                new SpotcheckItem("d", "q", "a", List.of(), false, false));

        assertThat(EvalScoreService.agreement(items)).isEqualTo(0.75);
    }

    @Test
    void agreementRefusesItemsTheHumanHasNotScored() {
        assertThatThrownBy(() -> EvalScoreService.agreement(
                List.of(new SpotcheckItem("a", "q", "a", List.of(), true, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }
}
