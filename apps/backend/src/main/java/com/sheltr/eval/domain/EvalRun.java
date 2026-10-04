package com.sheltr.eval.domain;

import java.util.List;
import java.util.Map;

public record EvalRun(String config, String startedAt, Map<String, String> settings, List<EvalResult> results) {
}
