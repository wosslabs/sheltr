package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.search.domain.RankedResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds which numbered passages an answer cites, written as [1], [2], and so on. */
public final class CitationSelector {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})\\]");

    private CitationSelector() {
    }

    public static List<Citation> select(String answer, List<RankedResult> passages, List<Citation> citations) {
        Set<ChunkId> cited = new HashSet<>();
        Matcher matcher = MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 1 && number <= passages.size()) {
                cited.add(passages.get(number - 1).chunkId());
            }
        }
        return citations.stream().filter(citation -> cited.contains(citation.chunkId())).toList();
    }
}
