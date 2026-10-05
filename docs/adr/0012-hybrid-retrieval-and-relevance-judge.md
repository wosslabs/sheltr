# ADR-0012: Staged retrieval behind ports, with the default chosen by eval

Status: Accepted (mechanism). The default implementation for each stage is not yet chosen; see Decision.
Date: 2026-10-04

## Context

Retrieval was a single vector query, and an answer was allowed when the top cosine score
passed a fixed threshold. Confidence labels came from the same score with hardcoded bands.
None of this had been measured on real documents.

## Decision

Answering runs through one `AnswerPipeline` (`apps/backend/src/main/java/com/sheltr/ai/application/AnswerPipeline.java`):
retrieve 20 candidates (`CANDIDATES`), rerank to 5 (`TOP_K`), judge whether the passages
answer the question, then generate. Each stage is a port:

- `Retriever`: `vector` (pgvector cosine search only) or `hybrid` (vector plus English
  keyword search, fused by reciprocal rank, then boosted for hazards reported active near
  the asker). Selected by `sheltr.search.mode`.
- `Reranker`: `none` (keep retrieval order) or `model` (a chat model orders the candidates).
  Selected by `sheltr.search.reranker`.
- `RelevanceJudge`: `threshold` (best passage score against `sheltr.search.relevance-threshold`)
  or `model` (a chat model returns `ANSWERABLE` and `PROBABILITY`; answerable only when
  the answer is yes and the probability reaches `sheltr.judge.min-probability`).
  Selected by `sheltr.judge.type`.

Active hazards raise matching passages and never filter passages out. Related hazards
raise passages by a smaller amount, under the same rule.

A failed reranker falls back to retrieval order. A failed or unreadable judge refuses.

### Shipped defaults

These are the values in `apps/backend/src/main/resources/application*.yml` today. They are
what the code runs, not a recommendation.

| Setting | Default | Where |
| --- | --- | --- |
| `sheltr.search.mode` | `vector` | `application.yml` |
| `sheltr.search.reranker` | `none` | `application.yml` |
| `sheltr.judge.type` | `threshold` | `application.yml` |
| `sheltr.judge.min-probability` | `0.5` (used only by the `model` judge) | `application.yml` |
| `sheltr.search.relevance-threshold` | `0.65` (`local`), `0.75` (`gemini`) | profile files |
| `sheltr.search.hazard-boost` / `related-hazard-boost` | `0.3` / `0.15` | `application.yml` |
| `sheltr.confidence.high` / `medium` | `0.90` / `0.75` | `application.yml` |

The `0.65` local threshold came from an earlier 40-case fixture sweep, not from the corpus
eval. The `0.75` Gemini value was carried over from Phase 2 and was never calibrated with
the eval. The confidence bands have not been refitted.

### Pending: the eval-based default choice

The eval harness (`make eval`, `make eval-score`) can compare configurations on the tuning
split. The choice of default `mode`, `reranker`, `judge.type`, the relevance threshold, and
the confidence bands is **not yet made**. It waits on two things:

- the maintainer's review of the corpus and the question set (plan Tasks 5 and 13), and
- the human-gated eval run (plan Task 14).

Until both are done, the defaults above stay as they are. No target has been met, and this
ADR does not claim one. When the choice is made, the results and the reasoning will be
recorded in a new section of this ADR or in a file under `docs/eval/`, and this table will
be updated to match the code in the same change.

## Consequences

- Defaults can change when the corpus or models change; rerun the eval before changing them.
- A model-based reranker or judge adds a model call per question.
- The threshold judge is only as good as its threshold. Until the eval picks one, the
  threshold is a tuned guess for the local embedding model.
- Graph RAG was considered and deferred (spec section 9). It is reconsidered as a separate
  experiment if recall at 5 on multi-step questions stays below 0.70 after hybrid search,
  reranking, and related hazards are in place.
