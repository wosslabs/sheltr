# Sheltr — Real Corpus, Eval Harness, and Retrieval Quality Design

Status: Draft — awaiting review
Date: 2026-10-04

## 1. Context

### 1.1 Product brief

Agreed on 2026-10-04:

- **Who:** anyone, in any country, on an inexpensive phone.
- **When:** before, during, and after a disaster.
- **How:** online AI chat with citations; offline pre-approved guidance cards with no AI.
- **Content:** official text only, approved by the maintainer. AI may select and shorten
  official text; it never writes new advice.
- **Language:** English only. No multilingual support in any sub-project for now.
- **Geography:** global. The corpus holds only guidance that applies anywhere; no country is
  singled out. The user picks a country of residence, and the app looks up current hazard
  alerts and weather near them ("local conditions").
- **Client:** installable web app. The Lumina chat UI stays as the admin and demo UI.
- **Cost:** USD 20–100 per month, hosted model, rate-limited.

Sheltr relays official alerts; it never predicts hazards or judges whether a place is safe.

### 1.2 Sub-project map

The work is built as a thin slice through every layer, then widened. Each sub-project gets
its own spec and plan.

1. **Real corpus, eval harness, retrieval quality** — this spec.
2. **Corpus breadth and guidance cards** — more connectors, refresh schedule, card model,
   approval screen in the Lumina admin.
3. **Public API** — local conditions service (alert and weather feeds), rate limits, hosted
   model profile hardening.
4. **Web app** — country picker, location permission, conditions display, online chat,
   offline cards.
5. **Deployment** — server, CI, public repository.

### 1.3 Why this sub-project comes first

Two facts about the code today:

- The 32 chunks seeded by `V5__seed_knowledge_documents.sql` are hand-written sentences
  attributed to FEMA and the American Red Cross, with generic URLs and placeholder checksums.
  They are not official text, yet answers cite them as sources.
- The only evaluation is `RelevanceThresholdEvalTest`: a 40-question sweep of the top-1
  cosine score over those 32 sentences. It measures nothing about real documents or about
  answer correctness.

Every later sub-project depends on answers being measurably correct, so the real corpus and
the measurement come before anything public.

## 2. Scope

**In scope**

- A reproducible baseline corpus of 20–30 official English documents of global guidance.
- Ingestion changes needed to load it: per-document metadata, licence recording.
- Seed rows excluded from real retrieval and kept as a test fixture.
- A wider hazard taxonomy and a hand-approved related-hazards table.
- An eval harness: question set, metrics, runner, reports.
- Retrieval pipeline: hybrid search, reranking, relevance judge, judge-based confidence,
  optional active-hazards context.
- A hosted generation model chosen by eval results.
- README corrected so it no longer claims the seed data is sourced guidance.

**Out of scope**

- Multilingual anything.
- Country-specific content, including emergency numbers.
- Live alert and weather feeds (sub-project 3). This spec only defines how retrieval and
  answering accept an "active hazards" input.
- Guidance cards, new source connectors beyond generic PDF-over-HTTP, the approval screen,
  the web app, rate limiting, deployment, CI.
- Graph RAG. See section 9 for the condition that would bring it in.

## 3. Corpus

### 3.1 Manifest

The corpus is defined by a file in the repository, `corpus/manifest.yaml`, so anyone can
rebuild it. One entry per document:

| Field | Meaning |
|---|---|
| `source_uri` | Direct URL of the PDF |
| `title` | Title as printed on the document |
| `source_agency` | Publishing organisation |
| `hazards` | One or more hazard types from section 3.4 |
| `published_date` | Date printed on the document |
| `license` | Reuse terms, with a URL to where they are stated |
| `checksum` | SHA-256 of the file when it was approved |

`make ingest-corpus` ingests every manifest entry not already present. A checksum mismatch
fails that entry: the document changed since it was approved and needs re-approval.

### 3.2 Selection rules

A document is eligible only if all of these hold:

- Published by an international body or an agency whose guidance is written for general
  use, such as Red Cross and Red Crescent, UN agencies, or the World Health Organization.
- Written in English, for the public or for community volunteers.
- Not tied to one country: no reliance on a national phone number, agency, or alert scheme.
  A document that is otherwise general but mentions one is acceptable; chunks that are
  purely country-specific are dropped at approval.
- Reuse terms allow storing and quoting the text with attribution. The terms are read per
  document and recorded in `license`. Documents with unclear terms are left out.

The maintainer approves each manifest entry. The specific documents are chosen during
implementation; this spec does not name them.

### 3.3 Ingestion changes

- `IngestDocumentService.ingest` takes the manifest entry's metadata. Today the title,
  agency, and hazards come from the connector (`SourceConnector.metadata()`), which fixes
  them per connector, and `published_date` is set to the ingest date. Both are wrong for a
  multi-publisher corpus.
- `FemaHttpConnector` is generalised to a PDF-over-HTTP connector with no publisher-specific
  metadata.
- Ingestion rejects an entry with an empty `license`.
- PDF remains the only supported source type.

### 3.4 Schema changes

One new Flyway migration:

- `knowledge.documents.license TEXT` — required for ingested documents.
- Seed documents get `status = 'SEED'`.
- `knowledge.chunks.embedding_model TEXT` — the model that produced the vector.
- `knowledge.chunks.content_tsv` becomes a generated column,
  `to_tsvector('english', content)`. The column and its GIN index exist today but are never
  populated.
- `HURRICANE` is renamed `TROPICAL_CYCLONE` in `disaster_types` arrays.
- New table `knowledge.related_hazards` (section 3.6).

`DisasterType` becomes: `EARTHQUAKE`, `FLOOD`, `TROPICAL_CYCLONE`, `TORNADO`, `TSUNAMI`,
`WILDFIRE`, `EXTREME_HEAT`, `WINTER_STORM`, `LANDSLIDE`, `GLACIAL_LAKE_FLOOD`, `VOLCANO`,
`DROUGHT`.

### 3.5 Seed data and stale embeddings

- Retrieval joins `knowledge.documents` and returns only chunks whose document status is in
  `sheltr.search.statuses` (default `ACTIVE`). Today retrieval ignores status entirely, so a
  retired document would still be cited.
- Tests that rely on the seed rows set `sheltr.search.statuses=ACTIVE,SEED`.
- Retrieval returns only chunks whose `embedding_model` equals the active model. At startup,
  if chunks exist with a different model name, the application logs how many and refuses to
  serve questions until they are re-embedded. This replaces the silent degradation described
  in the README.

### 3.6 Related hazards

`knowledge.related_hazards(hazard, related_hazard, source_document_id, note)` records that one
hazard commonly triggers another, for example an earthquake followed by landslides. Each row
must cite a corpus document that states the relationship. Rows are added by migration after
maintainer approval; nothing is extracted by a model.

## 4. Eval harness

### 4.1 Question set

`apps/backend/src/test/resources/eval/questions.jsonl`, about 150 entries:

| Field | Meaning |
|---|---|
| `id` | Stable identifier |
| `question` | As a member of the public would ask it |
| `kind` | `ANSWERABLE`, `MULTI_STEP`, `OFF_TOPIC`, or `UNCOVERED` |
| `active_hazards` | Optional hazards reported near the asker |
| `expected_passages` | List of `{source_uri, contains}`; `contains` is a verbatim phrase from the passage that answers the question |
| `key_facts` | Facts a correct answer must state |
| `split` | `tune` or `heldout` |

Passages are matched by phrase, not chunk ID, so the set survives re-ingestion and chunking
changes.

Composition:

- About 90 `ANSWERABLE`: one passage answers it.
- About 15 `MULTI_STEP`: needs passages about more than one hazard or document.
- About 20 `OFF_TOPIC`: unrelated to disasters. Must be refused.
- About 25 `UNCOVERED`: about disasters, but the corpus does not answer it. Must be refused.
  This is the most dangerous kind, since a plausible wrong answer is easy to produce.

About one third of the questions are `heldout`. Thresholds, prompts, and configuration are
tuned only on `tune`; targets are checked on `heldout`.

Questions are drafted from the corpus documents and reviewed by the maintainer before use.

### 4.2 Metrics

| Metric | Definition |
|---|---|
| Recall at 5 | Share of answerable questions with an expected passage in the top 5 |
| Mean reciprocal rank | Average of 1 / rank of the first expected passage |
| False-accept rate | Share of must-refuse questions that received an answer |
| False-refuse rate | Share of answerable questions that were refused |
| Faithfulness | Share of answer sentences supported by the cited passages |
| Key-fact coverage | Share of `key_facts` present in the answer |
| Calibration error | Gap between stated confidence and observed correctness, binned |
| Latency and cost | Median and 95th-percentile seconds, and cost per question |

Faithfulness and key-fact coverage are scored by a judge model that is not the generator
under test. To check the judge, the maintainer scores 30 answers by hand; agreement between
judge and maintainer is recorded in the report. If agreement is below 0.9, the judge prompt
is fixed before any result is trusted.

### 4.3 Runner and reports

- `make eval CONFIG=<name>` runs the question set against a named configuration and writes
  `eval/reports/<timestamp>-<name>.json` plus a Markdown summary.
- A configuration names the retrieval mode, reranker, relevance judge, embedding model, and
  generation profile.
- The runner is a command-line mode of the backend, like the existing `seed-embeddings`
  argument. It is not part of `mvn test`, since it needs live models.
- The baseline report (current pipeline on the real corpus) and the report for the chosen
  configuration are committed under `docs/eval/`.

## 5. Retrieval and answering pipeline

```
question (+ active hazards)
  → vector search, top 20        ┐
  → keyword search, top 20       ┴→ rank fusion
  → hazard boost (active hazards and their related hazards)
  → Reranker → top 5
  → RelevanceJudge → refuse, or generate with citations
```

Each stage is added behind a configuration switch and kept only if the eval improves on the
`tune` split without worsening false accepts.

### 5.1 Ports and implementations

| Port | Package | Implementations |
|---|---|---|
| `Retriever` (existing) | `search/domain` | Vector-only (existing); hybrid, fusing vector and keyword rankings by reciprocal rank |
| `Reranker` (new) | `search/domain` | No-op; model-based, using the configured chat model to order candidates |
| `RelevanceJudge` (new) | `ai/domain` | Threshold on top score (today's behaviour); model-based; Jev |

- `SearchQuery` gains an optional set of active hazards.
- `RelevanceJudge` returns a verdict: answerable or not, with a probability.
- `AskQuestionService` calls `RelevanceJudge` in place of `RelevanceGate`.

A dedicated reranking model is considered only if the model-based reranker shows a gain
worth the extra service.

### 5.2 Active hazards

- Active hazards boost matching passages; they never filter. A user under a flood alert may
  still ask about earthquakes.
- Related hazards (section 3.6) are added to the boost set at lower weight.
- The prompt states the active hazards as reported context. The model is instructed not to
  assert that a hazard is or is not occurring.
- `AskRequestDto` accepts an optional `activeHazards` list. Sub-project 3 fills it from live
  feeds; until then it is supplied only by tests and the eval set.

### 5.3 Relevance judge and confidence

- The model-based judge is asked whether the retrieved passages answer the question and
  returns a structured verdict.
- Probabilities reported by a model are not assumed to be calibrated. Confidence bands
  (`HIGH`, `MEDIUM`, `LOW`) are fitted on the `tune` split and checked on `heldout`.
- Bands live in configuration per judge, replacing the hardcoded 0.90 and 0.75 in
  `AnswerConfidence`.
- **Jev** is an opt-in implementation behind a `jev` profile. It is built only if early
  access is granted, is never the default, and is adopted only if it beats the model-based
  judge on false accepts, calibration, or cost.

### 5.4 Answer generation

- Context passages are numbered in the prompt, and the answer marks which passage supports
  each statement.
- Citations list only the passages the answer used. Today every retrieved passage is listed
  as a citation whether used or not.

## 6. Models

- **Embedding:** `nomic-embed-text` remains the baseline. One alternative English embedding
  model is evaluated; it is adopted only if the gain justifies re-embedding and, if its
  dimension differs, a column migration.
- **Generation:** candidates are the local `qwen2.5:3b-instruct`, the existing Gemini
  profile, and a new Claude Haiku profile. The hosted default is the candidate that meets
  the targets in section 7 at the lowest cost per question.
- The `local` profile stays the default for development and needs no API key.

## 7. Targets

Measured on the `heldout` split with the chosen configuration:

| Metric | Target |
|---|---|
| False-accept rate | 0 |
| False-refuse rate | at most 0.10 |
| Recall at 5 | at least 0.85 |
| Faithfulness | at least 0.95 |
| Judge and maintainer agreement | at least 0.90 |

If a target is missed, the report says so and the sub-project is not complete.

## 8. Error handling and testing

**Failure behaviour**

- Reranker fails: use the fused order and log it.
- Relevance judge fails: refuse. The pipeline fails closed.
- Manifest entry has no licence or a checksum mismatch: that entry is rejected; others
  continue.
- Chunks from another embedding model exist: refuse to serve questions until re-embedded.

**Tests**

- Unit: rank fusion, hazard boost, each judge, manifest parsing, each metric calculation.
- Integration with Testcontainers: hybrid SQL, status and embedding-model filters, the
  migration.
- Existing tests keep passing using the seed fixture.
- The eval run is gated by environment variable, as `RelevanceThresholdEvalTest` is today.
  That test is retired once the harness reproduces its result.

## 9. Graph RAG

Not built in this sub-project. Sheltr's questions are narrow and procedural, which suits
passage retrieval; Graph RAG answers from model-written summaries, which conflicts with the
official-text-only rule; and indexing calls a model on every passage.

It is reconsidered as a separate experiment if recall at 5 on `MULTI_STEP` questions is
below 0.70 after hybrid search, reranking, and related hazards are in place.

## 10. Documentation

- New ADR: hybrid retrieval, reranker, and relevance judge.
- New ADR: corpus manifest and the official-text-only rule.
- ADR-0008 (mandatory citation grounding) amended for used-passage citations.
- README: state that seed rows are a test fixture, describe `make ingest-corpus` and
  `make eval`, and replace the embedding-switch warning with the startup check.
