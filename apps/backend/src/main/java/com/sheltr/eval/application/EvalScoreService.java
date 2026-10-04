package com.sheltr.eval.application;

import com.sheltr.eval.domain.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EvalScoreService {

    public static final int SPOTCHECK_SIZE = 30;

    public record Output(EvalReport report, List<SpotcheckItem> spotcheck) {
    }

    private record Judged(Metrics.Scored scored, double faithfulness, double keyFactCoverage) {
        boolean correct() {
            return !scored.question().kind().mustRefuse() && faithfulness == 1.0 && keyFactCoverage == 1.0;
        }
    }

    private final AnswerJudge judge;

    public EvalScoreService(AnswerJudge judge) {
        this.judge = judge;
    }

    public Output score(List<EvalQuestion> questions, EvalRun run) {
        List<String> questionIds = questions.stream().map(EvalQuestion::id).toList();
        List<String> duplicateQuestions = duplicates(questionIds);
        if (!duplicateQuestions.isEmpty()) {
            throw new IllegalArgumentException("Duplicate question ID(s) in the question file: " + duplicateQuestions);
        }
        List<String> resultIds = run.results().stream().map(EvalResult::questionId).toList();
        List<String> duplicateResults = duplicates(resultIds);
        if (!duplicateResults.isEmpty()) {
            throw new IllegalArgumentException("Duplicate result ID(s) in the run: " + duplicateResults);
        }
        List<String> strays = resultIds.stream().filter(id -> !questionIds.contains(id)).toList();
        if (!strays.isEmpty()) {
            throw new IllegalArgumentException("The run has results for ID(s) not in the question file: " + strays);
        }

        Map<String, EvalResult> results = run.results().stream()
                .collect(Collectors.toMap(EvalResult::questionId, Function.identity()));
        List<String> missing = questions.stream().map(EvalQuestion::id)
                .filter(id -> !results.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The run has no result for question(s): " + missing);
        }

        List<Metrics.Scored> scored = questions.stream()
                .map(question -> new Metrics.Scored(question, results.get(question.id()))).toList();
        List<Judged> judged = new ArrayList<>();
        for (Metrics.Scored item : scored) {
            if (item.result().refused()) {
                continue;
            }
            List<String> cited = item.result().cited().stream().map(RetrievedPassage::content).toList();
            double faithfulness = judge.faithfulness(item.result().answer(), cited);
            double coverage = item.question().keyFacts().isEmpty() ? Double.NaN
                    : judge.keyFactCoverage(item.result().answer(), item.question().keyFacts());
            judged.add(new Judged(item, faithfulness, coverage));
        }

        Map<String, SplitScores> splits = new LinkedHashMap<>();
        for (String split : List.of("tune", "heldout")) {
            splits.put(split, scoreSplit(
                    scored.stream().filter(s -> s.question().split().equals(split)).toList(),
                    judged.stream().filter(j -> j.scored().question().split().equals(split)).toList()));
        }

        List<Judged> tune = judged.stream().filter(j -> j.scored().question().split().equals("tune")).toList();
        EvalReport report = new EvalReport(run.config(), run.settings(), splits, confidenceTable(tune),
                judge.errors());
        return new Output(report, spotcheck(judged));
    }

    public static double agreement(List<SpotcheckItem> items) {
        List<String> unscored = items.stream().filter(item -> item.humanFaithful() == null)
                .map(SpotcheckItem::id).toList();
        if (!unscored.isEmpty()) {
            throw new IllegalArgumentException("human_faithful is not filled in for: " + unscored);
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("The spotcheck file is empty");
        }
        long agreed = items.stream().filter(item -> item.judgeFaithful() == item.humanFaithful()).count();
        return (double) agreed / items.size();
    }

    private static List<String> duplicates(List<String> ids) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        return List.copyOf(duplicates);
    }

    private static SplitScores scoreSplit(List<Metrics.Scored> scored, List<Judged> judged) {
        List<Long> latencies = scored.stream().map(s -> s.result().latencyMs()).toList();
        List<double[]> calibration = judged.stream()
                .filter(j -> j.scored().result().confidence() != null)
                .map(j -> new double[]{j.scored().result().confidence(), j.correct() ? 1 : 0})
                .toList();
        return new SplitScores(
                scored.size(),
                Metrics.recallAtK(scored, 5),
                Metrics.meanReciprocalRank(scored),
                Metrics.falseAcceptRate(scored),
                Metrics.falseRefuseRate(scored),
                judged.stream().mapToDouble(Judged::faithfulness).average().orElse(Double.NaN),
                judged.stream().mapToDouble(Judged::keyFactCoverage).filter(v -> !Double.isNaN(v))
                        .average().orElse(Double.NaN),
                Metrics.calibrationError(calibration, 5),
                Metrics.percentile(latencies, 0.5),
                Metrics.percentile(latencies, 0.95),
                (int) scored.stream().filter(s -> s.result().error() != null).count());
    }

    private static List<ConfidenceRow> confidenceTable(List<Judged> tune) {
        List<ConfidenceRow> rows = new ArrayList<>();
        for (int step = 10; step <= 19; step++) {
            double threshold = step / 20.0;
            List<Judged> atLeast = tune.stream()
                    .filter(j -> j.scored().result().confidence() != null
                            && j.scored().result().confidence() >= threshold)
                    .toList();
            double accuracy = atLeast.isEmpty() ? Double.NaN
                    : (double) atLeast.stream().filter(Judged::correct).count() / atLeast.size();
            rows.add(new ConfidenceRow(threshold, atLeast.size(), accuracy));
        }
        return rows;
    }

    private static List<SpotcheckItem> spotcheck(List<Judged> judged) {
        List<Judged> shuffled = new ArrayList<>(judged);
        // Fixed seed: the same run always yields the same sample.
        Collections.shuffle(shuffled, new Random(42));
        return shuffled.stream().limit(SPOTCHECK_SIZE)
                .map(j -> new SpotcheckItem(j.scored().question().id(), j.scored().question().question(),
                        j.scored().result().answer(),
                        j.scored().result().cited().stream().map(RetrievedPassage::content).toList(),
                        j.faithfulness() == 1.0, null))
                .toList();
    }
}
