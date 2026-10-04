package com.sheltr.eval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sheltr.eval.domain.EvalQuestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EvalQuestionLoader {

    private static final Set<String> SPLITS = Set.of("tune", "heldout");

    private EvalQuestionLoader() {
    }

    public static List<EvalQuestion> load(Path path) throws IOException {
        List<EvalQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String where = "line " + (i + 1);
            EvalQuestion question;
            try {
                question = EvalJson.MAPPER.readValue(line, EvalQuestion.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(where + ": not valid JSON: " + e.getOriginalMessage());
            }
            validate(question, where);
            if (!ids.add(question.id())) {
                throw new IllegalArgumentException(where + ": duplicate id " + question.id());
            }
            questions.add(question);
        }
        return questions;
    }

    private static void validate(EvalQuestion question, String where) {
        if (question.id() == null || question.id().isBlank()) {
            throw new IllegalArgumentException(where + ": id is required");
        }
        where = where + " (" + question.id() + ")";
        if (question.question() == null || question.question().isBlank()) {
            throw new IllegalArgumentException(where + ": question is required");
        }
        if (question.kind() == null) {
            throw new IllegalArgumentException(where + ": kind is required");
        }
        if (question.split() == null || !SPLITS.contains(question.split())) {
            throw new IllegalArgumentException(where + ": split must be 'tune' or 'heldout'");
        }
        if (question.kind().mustRefuse()) {
            if (!question.expectedPassages().isEmpty()) {
                throw new IllegalArgumentException(
                        where + ": a " + question.kind() + " question must not have expected_passages");
            }
        } else {
            if (question.expectedPassages().isEmpty()) {
                throw new IllegalArgumentException(where + ": expected_passages is required");
            }
            if (question.keyFacts().isEmpty()) {
                throw new IllegalArgumentException(where + ": key_facts is required");
            }
            if (question.expectedPassages().stream().anyMatch(passage -> passage.sourceUri() == null
                    || passage.contains() == null || passage.contains().isBlank())) {
                throw new IllegalArgumentException(
                        where + ": every expected passage needs source_uri and contains");
            }
        }
    }
}
