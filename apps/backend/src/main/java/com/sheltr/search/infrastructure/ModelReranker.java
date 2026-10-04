package com.sheltr.search.infrastructure;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks a chat model to order the candidates. Any failure falls back to the incoming order. */
public class ModelReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ModelReranker.class);
    /** The first comma-separated list of numbers in the reply; later numbers in prose are ignored. */
    private static final Pattern RANKING = Pattern.compile("\\d+(?:\\s*,\\s*\\d+)+");

    private final UnaryOperator<String> llm;

    public ModelReranker(UnaryOperator<String> llm) {
        this.llm = llm;
    }

    @Override
    public List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK) {
        int limit = Math.max(0, topK);
        if (candidates.size() <= 1) {
            return candidates.stream().limit(limit).toList();
        }
        Set<Integer> order = new LinkedHashSet<>();
        try {
            String reply = llm.apply(prompt(query, candidates));
            Matcher ranking = RANKING.matcher(reply);
            String list = ranking.find() ? ranking.group() : reply.strip();
            for (String token : list.split(",")) {
                int index = parse(token.strip()) - 1;
                if (index >= 0 && index < candidates.size()) {
                    order.add(index);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Reranker failed, keeping retrieval order", e);
            order.clear();
        }
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        List<RankedResult> reranked = new ArrayList<>();
        for (int index : order) {
            reranked.add(candidates.get(index));
        }
        return reranked.stream().limit(limit).toList();
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String prompt(SearchQuery query, List<RankedResult> candidates) {
        StringBuilder prompt = new StringBuilder("""
                Rank the passages by how well they answer the question. Reply with the passage \
                numbers only, best first, separated by commas. Example: 3,1,2

                Question: %s

                Passages:
                """.formatted(query.text()));
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append('[').append(i + 1).append("] ").append(candidates.get(i).content()).append('\n');
        }
        return prompt.toString();
    }
}
