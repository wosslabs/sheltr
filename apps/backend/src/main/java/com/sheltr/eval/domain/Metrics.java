package com.sheltr.eval.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Metrics {

    public record Scored(EvalQuestion question, EvalResult result) {
    }

    private Metrics() {
    }

    public static boolean matches(ExpectedPassage expected, RetrievedPassage passage) {
        return expected.sourceUri().equals(passage.sourceUri())
                && normalise(passage.content()).contains(normalise(expected.contains()));
    }

    /** ANSWERABLE needs any expected passage in the top k; MULTI_STEP needs all of them. */
    public static boolean hit(EvalQuestion question, List<RetrievedPassage> passages, int k) {
        List<RetrievedPassage> top = passages.stream().limit(k).toList();
        if (question.expectedPassages().isEmpty()) {
            return false;
        }
        if (question.kind() == EvalKind.MULTI_STEP) {
            return question.expectedPassages().stream()
                    .allMatch(expected -> top.stream().anyMatch(passage -> matches(expected, passage)));
        }
        return question.expectedPassages().stream()
                .anyMatch(expected -> top.stream().anyMatch(passage -> matches(expected, passage)));
    }

    /** One-based rank of the first retrieved passage matching any expected passage; 0 when none does. */
    public static int firstHitRank(EvalQuestion question, List<RetrievedPassage> passages) {
        for (int i = 0; i < passages.size(); i++) {
            RetrievedPassage passage = passages.get(i);
            if (question.expectedPassages().stream().anyMatch(expected -> matches(expected, passage))) {
                return i + 1;
            }
        }
        return 0;
    }

    public static double recallAtK(List<Scored> scored, int k) {
        List<Scored> answerable = answerable(scored);
        return ratio(answerable.stream().filter(s -> hit(s.question(), s.result().passages(), k)).count(),
                answerable.size());
    }

    public static double meanReciprocalRank(List<Scored> scored) {
        List<Scored> answerable = answerable(scored);
        if (answerable.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Scored s : answerable) {
            int rank = firstHitRank(s.question(), s.result().passages());
            sum += rank == 0 ? 0.0 : 1.0 / rank;
        }
        return sum / answerable.size();
    }

    public static double falseAcceptRate(List<Scored> scored) {
        List<Scored> mustRefuse = scored.stream().filter(s -> s.question().kind().mustRefuse()).toList();
        return ratio(mustRefuse.stream().filter(s -> !s.result().refused()).count(), mustRefuse.size());
    }

    public static double falseRefuseRate(List<Scored> scored) {
        List<Scored> answerable = answerable(scored);
        return ratio(answerable.stream().filter(s -> s.result().refused()).count(), answerable.size());
    }

    /**
     * Expected calibration error over equal-width confidence bins.
     *
     * @param confidenceAndCorrect pairs of {confidence in 0..1, 1 if correct else 0}
     */
    public static double calibrationError(List<double[]> confidenceAndCorrect, int bins) {
        if (confidenceAndCorrect.isEmpty()) {
            return Double.NaN;
        }
        double[] confidenceSum = new double[bins];
        double[] correctSum = new double[bins];
        int[] count = new int[bins];
        for (double[] pair : confidenceAndCorrect) {
            int bin = Math.min(bins - 1, (int) (pair[0] * bins));
            confidenceSum[bin] += pair[0];
            correctSum[bin] += pair[1];
            count[bin]++;
        }
        double error = 0;
        for (int bin = 0; bin < bins; bin++) {
            if (count[bin] > 0) {
                error += Math.abs(confidenceSum[bin] / count[bin] - correctSum[bin] / count[bin])
                        * count[bin] / confidenceAndCorrect.size();
            }
        }
        return error;
    }

    public static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static List<Scored> answerable(List<Scored> scored) {
        return scored.stream().filter(s -> !s.question().kind().mustRefuse()).toList();
    }

    private static double ratio(long numerator, int denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
