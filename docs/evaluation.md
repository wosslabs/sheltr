# Search evaluation

How well the on-device keyword search answers, measured on the sample bundle
(five documents including the IFRC 2018 key-messages PDF,
`corpus/sample-bundle`). The tests are `retrieval cases have no false accepts`
and `retrieval cases answer and keep the expected term` in
`apps/flutter/packages/sheltr_core/test/retrieval_test.dart`. The questions are
in `contract/retrieval-cases.tsv`. Run them with:

```bash
cd apps/flutter/packages/sheltr_core && dart test test/retrieval_test.dart
```

## Result (2026-11-04)

| Measure | Result | Target |
|---|---|---|
| False accepts: off-topic questions that got an answer | **0 of 20** | 0 |
| Answerable questions answered | 31 of 31 (100%) | at least 90% |
| Answerable questions with the expected passage in the top 5 | 29 of 31 (94%) | at least 90% |

The sample corpus grew to include the IFRC PDF on 2026-11-03. That document
uses ordinary English words (`tires`, `letter`, `change`, `write`) that made
two off-topic questions (`flat tire`, `cover letter`) pass the old gate. Fixes:

1. Weak query words that do not carry topic on their own (`change`, `write`,
   `tips`, `meaning`, `life`) are stopwords in `contract/retrieval-rules.json`.
2. Retrieval returns only passages that clear score and term coverage, ordered
   by coverage then score, and always searches a wider pool (`poolMultiplier`).

Country scoping (ADR-0018) is separate: a selected country keeps that country's
passages when they pass, otherwise global guidance — not a boosted mix of every
country.

## Known misses

- "what should I do during an earthquake": answered from IFRC earthquake pages,
  but the expected phrase "cover your head" is not in the top 5.
- "how can I save water during a drought": answered, but the passage about
  turning off taps is not in the top 5.

## Limits

- The set is small (51 questions) and written by the maintainers. It guards
  against regressions; it does not prove quality on questions nobody thought of.
  Add questions whenever the corpus grows or a real question fails.
- Five documents. Results will change with a larger corpus, so re-run it before
  every corpus change.
- English only. The stopword list and word matching assume English documents.
- Keyword search does not understand meaning. A question worded very differently
  from the text is refused. An on-device model is only worth adding if this
  evaluation, extended with real questions, shows the refusal rate is too high.

## On-device model

A later device run of one LiteRT-LM file is described in
[`docs/model-eval.md`](model-eval.md). The settings default stays off. That page
records the procedure and an empty result table. No device run is recorded there
yet.
