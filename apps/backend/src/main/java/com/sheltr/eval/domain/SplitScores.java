package com.sheltr.eval.domain;

public record SplitScores(int questions, double recallAt5, double meanReciprocalRank, double falseAcceptRate,
                          double falseRefuseRate, double faithfulness, double keyFactCoverage,
                          double calibrationError, long medianLatencyMs, long p95LatencyMs, int errors) {
}
