package com.sheltr.eval.domain;

import java.util.List;
import java.util.Map;

public record EvalReport(String config, Map<String, String> settings, Map<String, SplitScores> splits,
                         List<ConfidenceRow> tuneConfidenceTable, int judgeErrors) {
}
