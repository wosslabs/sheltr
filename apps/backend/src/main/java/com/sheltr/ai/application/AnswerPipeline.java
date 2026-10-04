package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.domain.AnswerOutcome;
import com.sheltr.ai.domain.NoAnswerAvailable;
import com.sheltr.ai.domain.RelevanceJudge;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Retrieve, rerank, judge, then generate. The one place that order is defined. */
public class AnswerPipeline {

    public static final int CANDIDATES = 20;
    public static final int TOP_K = 5;

    private static final Logger log = LoggerFactory.getLogger(AnswerPipeline.class);

    public record JudgedPassages(List<RankedResult> passages, RelevanceVerdict verdict) {
    }

    public record PipelineResult(List<RankedResult> passages, RelevanceVerdict verdict, AnswerOutcome outcome) {
    }

    private final Retriever retriever;
    private final Reranker reranker;
    private final RelevanceJudge judge;
    private final AnswerGenerator answerGenerator;

    public AnswerPipeline(Retriever retriever, Reranker reranker, RelevanceJudge judge,
                          AnswerGenerator answerGenerator) {
        this.retriever = retriever;
        this.reranker = reranker;
        this.judge = judge;
        this.answerGenerator = answerGenerator;
    }

    public JudgedPassages retrieve(SearchQuery query) {
        List<RankedResult> candidates = retriever.retrieve(query, CANDIDATES);
        List<RankedResult> passages = rerank(query, candidates);
        if (passages.isEmpty()) {
            return new JudgedPassages(passages, RelevanceVerdict.REFUSE);
        }
        return new JudgedPassages(passages, judge(query, passages));
    }

    public PipelineResult answer(SearchQuery query) {
        JudgedPassages judged = retrieve(query);
        if (!judged.verdict().answerable()) {
            return new PipelineResult(judged.passages(), judged.verdict(), new NoAnswerAvailable());
        }
        return new PipelineResult(judged.passages(), judged.verdict(),
                answerGenerator.generate(query, judged.passages(), judged.verdict()));
    }

    // A failed reranker costs ranking quality, not safety: keep the retrieval order.
    private List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates) {
        try {
            return reranker.rerank(query, candidates, TOP_K);
        } catch (RuntimeException e) {
            log.warn("Reranker failed, keeping retrieval order: {}", e.getMessage());
            return candidates.stream().limit(TOP_K).toList();
        }
    }

    // A failed judge could let an unsupported answer through: refuse.
    private RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages) {
        try {
            RelevanceVerdict verdict = judge.judge(query, passages);
            if (verdict == null) {
                log.warn("Relevance judge returned no verdict, refusing");
                return RelevanceVerdict.REFUSE;
            }
            return verdict;
        } catch (RuntimeException e) {
            log.warn("Relevance judge failed, refusing: {}", e.getMessage());
            return RelevanceVerdict.REFUSE;
        }
    }
}
