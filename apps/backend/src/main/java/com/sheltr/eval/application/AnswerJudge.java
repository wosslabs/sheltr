package com.sheltr.eval.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Scores answers with a chat model. Run it under a different model than the one that wrote
 * the answers. Anything the judge cannot read counts against the answer and is counted in
 * {@link #errors()}.
 */
public class AnswerJudge {

    private final UnaryOperator<String> llm;
    private int errors;

    public AnswerJudge(UnaryOperator<String> llm) {
        this.llm = llm;
    }

    public double faithfulness(String answer, List<String> passages) {
        List<String> sentences = sentences(answer);
        if (sentences.isEmpty()) {
            return 0.0;
        }
        String context = String.join("\n", passages);
        long supported = sentences.stream().filter(sentence -> verdict("""
                Passages:
                %s

                Sentence: %s

                Is every claim in the sentence stated in the passages? Reply with one word: \
                SUPPORTED or UNSUPPORTED.
                """.formatted(context, sentence), "SUPPORTED", "UNSUPPORTED")).count();
        return (double) supported / sentences.size();
    }

    public double keyFactCoverage(String answer, List<String> keyFacts) {
        if (keyFacts.isEmpty()) {
            return Double.NaN;
        }
        long stated = keyFacts.stream().filter(fact -> verdict("""
                Answer: %s

                Fact: %s

                Does the answer state this fact? Reply with one word: YES or NO.
                """.formatted(answer, fact), "YES", "NO")).count();
        return (double) stated / keyFacts.size();
    }

    public int errors() {
        return errors;
    }

    private boolean verdict(String prompt, String positive, String negative) {
        try {
            String reply = llm.apply(prompt).trim().toUpperCase(Locale.ROOT);
            // Check the negative word first: "UNSUPPORTED" contains "SUPPORTED".
            if (reply.startsWith(negative)) {
                return false;
            }
            if (reply.startsWith(positive)) {
                return true;
            }
        } catch (RuntimeException e) {
            // counted below
        }
        errors++;
        return false;
    }

    private static List<String> sentences(String answer) {
        if (answer == null) {
            return List.of();
        }
        return Arrays.stream(answer.replaceAll("\\s*\\[\\d{1,3}\\]", "").split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(sentence -> !sentence.isEmpty())
                .toList();
    }
}
