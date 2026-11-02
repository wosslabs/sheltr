# Search evaluation

How well the on-device keyword search answers, measured on the shipped sample bundle (two documents, 127 chunks). The test is `src/core/retrieval/evaluation.test.ts` in `apps/mobile`; the questions are in `apps/mobile/src/core/testing/retrieval-eval.tsv`. Run it with:

```bash
cd apps/mobile && npx jest src/core/retrieval/evaluation.test.ts --verbose
```

## Result (2026-11-01)

| Measure | Result | Target |
|---|---|---|
| False accepts: off-topic questions that got an answer | **0 of 20** | 0 |
| Answerable questions answered | 29 of 31 (94%) | at least 90% |
| Answerable questions with the expected passage in the top 5 | 28 of 31 (90%) | at least 90% |

The evaluation found a real defect before this release: with the first version, **16 of 20 off-topic questions were answered** ("how do I file my income taxes", "best pizza recipe"). Two causes:

1. Function words ("how", "do", "I", "my") were searched. They occur in many chunks, so their scores added up past the relevance threshold. Fixed by removing stopwords from the query (`Stopwords.kt`).
2. One generic word that the corpus happens to use ("cover", "change", "world") was enough to pass. Fixed by requiring a passage to contain at least 60% of the question's topical words (`MIN_TERM_COVERAGE`), matching word forms such as "aftershocks"/"aftershock".

## Known misses

- "how do I stay safe in extreme heat" and "how do I evacuate safely" are refused: the best passage contains too few of their topical words. Refusing is the safe failure.
- "how can I save water during a drought": the answer is given, but the passage about turning off taps is not in the top 5.

## Limits

- The set is small (51 questions) and written by the maintainers. It guards against regressions; it does not prove quality on questions nobody thought of. Add questions whenever the corpus grows or a real question fails.
- Two documents only. Results will change with a larger corpus, so re-run it before every corpus change.
- English only. The stopword list and word matching assume English documents.
- Keyword search does not understand meaning. A question worded very differently from the text is refused. An on-device model is only worth adding if this evaluation, extended with real questions, shows the refusal rate is too high.
