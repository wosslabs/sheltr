package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.RelevanceJudge;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks a chat model whether the passages answer the question. Fails closed on any problem. */
public class ModelRelevanceJudge implements RelevanceJudge {

    private static final Logger log = LoggerFactory.getLogger(ModelRelevanceJudge.class);
    private static final Pattern ANSWERABLE = Pattern.compile("ANSWERABLE:\\s*(yes|no)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROBABILITY = Pattern.compile("PROBABILITY:\\s*([0-9]*\\.?[0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private final UnaryOperator<String> llm;
    private final double minProbability;

    public ModelRelevanceJudge(UnaryOperator<String> llm, double minProbability) {
        this.llm = llm;
        this.minProbability = minProbability;
    }

    @Override
    public RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages) {
        if (passages.isEmpty()) {
            return RelevanceVerdict.REFUSE;
        }
        try {
            String reply = llm.apply(prompt(query, passages));
            Matcher answerable = ANSWERABLE.matcher(reply);
            Matcher probability = PROBABILITY.matcher(reply);
            if (!answerable.find() || !probability.find()) {
                log.warn("Relevance judge reply was unreadable, refusing");
                return RelevanceVerdict.REFUSE;
            }
            double p = Double.parseDouble(probability.group(1));
            if (p < 0.0 || p > 1.0) {
                log.warn("Relevance judge returned probability {} outside 0..1, refusing", p);
                return RelevanceVerdict.REFUSE;
            }
            boolean yes = answerable.group(1).equalsIgnoreCase("yes");
            return new RelevanceVerdict(yes && p >= minProbability, p);
        } catch (RuntimeException e) {
            log.warn("Relevance judge failed, refusing: {}", e.getMessage());
            return RelevanceVerdict.REFUSE;
        }
    }

    private static String prompt(SearchQuery query, List<RankedResult> passages) {
        StringBuilder prompt = new StringBuilder("""
                You check whether passages from official disaster guidance contain the answer to a \
                question. Judge only what the passages say. If they are about a related topic but do \
                not answer this question, the answer is no.

                Reply in exactly this form:
                ANSWERABLE: yes or no
                PROBABILITY: a number from 0.00 to 1.00, your probability that the passages fully answer it

                Question: %s

                Passages:
                """.formatted(query.text()));
        for (int i = 0; i < passages.size(); i++) {
            prompt.append('[').append(i + 1).append("] ").append(passages.get(i).content()).append('\n');
        }
        return prompt.toString();
    }
}
