package com.sheltr.eval.domain;

/** Of the answers given with at least this confidence, how many there were and what share were correct. */
public record ConfidenceRow(double threshold, int answers, double accuracy) {
}
