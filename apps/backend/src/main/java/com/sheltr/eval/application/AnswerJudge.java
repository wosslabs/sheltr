package com.sheltr.eval.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores answers with a chat model. Run it under a different model than the one that wrote
 * the answers. Anything the judge cannot read counts against the answer and is counted in
 * {@link #errors()}.
 */
public class AnswerJudge {

    /** Citation markers such as [1], [1, 2] and [1-3]. */
    private static final String CITATION = "\\s*\\[\\d{1,3}(?:\\s*[,\\-\u2013]\\s*\\d{1,3})*\\]";
    private static final Pattern LEADING_WORD = Pattern.compile("[A-Z]+");

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
            // Skip markdown, quotes and punctuation before the word, then read the first whole word.
            Matcher word = LEADING_WORD.matcher(reply.replaceFirst("^[^A-Z]+", ""));
            if (word.lookingAt()) {
                String first = word.group();
                // Check the negative word first: a reply that starts with UNSUPPORTED is never SUPPORTED.
                if (first.equals(negative)) {
                    return false;
                }
                if (first.equals(positive)) {
                    return true;
                }
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
        return Arrays.stream(answer.replaceAll(CITATION, "").split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(sentence -> !sentence.isEmpty())
                .toList();
    }
}
