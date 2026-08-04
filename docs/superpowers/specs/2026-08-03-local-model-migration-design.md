# Sheltr — Local/CPU-Capable Model Migration Design

Status: Approved
Date: 2026-08-03

## 1. Scope

Sheltr is moving toward an open-source release. ADR-0003 currently mandates Gemini as the
*only* LLM/embedding provider — a hard product requirement that means every self-hoster needs
a Google API key and internet access just to run the app. That's a real adoption barrier for
an open-source project, and a single point of failure for a tool meant to answer
disaster-response questions.

This spec adds a local, CPU-capable model path via Ollama, served alongside (not replacing)
the existing Gemini path, and switches the *default* profile to local so a stranger cloning
the repo gets a fully working, offline, zero-signup experience.

**In scope:**
- Ollama sidecar added to `infra/podman/compose.yaml`, running `qwen2.5:3b-instruct`
  (generation, Apache-2.0 licensed) and `nomic-embed-text` (embedding, 768-dim, matches the
  existing `pgvector` schema — no migration needed).
- Spring profile split: `gemini` (existing behavior) and `local` (new, default).
- Renaming the existing Gemini adapters to provider-neutral names, since they already depend
  only on Spring AI's portable `ChatModel`/`EmbeddingModel` interfaces (ADR-0002/0003
  compliance already made this cheap — no Gemini SDK type leaks outside `infrastructure`).
- Empirical recalibration of `sheltr.search.relevance-threshold` for the new embedding space,
  via a small labeled eval set and threshold sweep.
- A local-profile variant of the existing Gemini-gated E2E test, so the local path has the
  same end-to-end proof Gemini has.
- ADR-0003 amendment + a new ADR recording local-as-default.

**Explicitly out of scope** (deferred to the safety-audit and open-source-release subprojects
that follow this one): the CI pipeline itself, broader error-handling/rate-limiting audit,
admin-endpoint authentication (existing tracked gap, unrelated to model provider), a
Gemini-vs-Qwen answer-quality comparison beyond the relevance-threshold eval, async/queued
ingestion.

## 2. Architecture

```
EmbeddingGenerator port (knowledge/domain)          AnswerGenerator port (ai/domain)
  ← SpringAiEmbeddingGeneratorAdapter                  ← SpringAiAnswerGeneratorAdapter
      (knowledge/infrastructure)                           (ai/infrastructure)
      wraps Spring AI's EmbeddingModel                      wraps Spring AI's ChatModel
```

Both adapters are renamed from `Gemini*Adapter` to `SpringAi*Adapter` — their logic doesn't
change at all, they already only depend on Spring AI's portable interfaces. Which concrete
provider backs those interfaces is decided entirely by which Spring profile is active:

- **`gemini` profile**: `spring-ai-google-genai-spring-boot-starter` autoconfigures
  `ChatModel`/`EmbeddingModel` against Google's API, reading `GEMINI_API_KEY`.
- **`local` profile** (new default): `spring-ai-ollama-spring-boot-starter` autoconfigures the
  same interfaces against a local Ollama server (`infra/podman/compose.yaml` sidecar,
  `qwen2.5:3b-instruct` for chat, `nomic-embed-text` for embedding). No API key, no external
  network call at request time.

No new adapter classes, no branching logic in application code — `IngestDocumentService`,
`AskQuestionService`, and every other consumer of `EmbeddingGenerator`/`AnswerGenerator`
already work purely against domain ports and are unaffected by which profile is active.

## 3. Configuration

- `application.yml` keeps only profile-agnostic keys (datasource, Flyway, server, management,
  and `spring.profiles.active: local` as the new default, overridable via
  `SPRING_PROFILES_ACTIVE=gemini`).
- `application-gemini.yml` (new, carries forward today's Gemini keys unchanged):
  ```yaml
  spring:
    ai:
      google:
        genai:
          api-key: ${GEMINI_API_KEY}
          chat:
            options:
              model: gemini-2.5-flash
          embedding:
            api-key: ${GEMINI_API_KEY}
            text:
              options:
                model: gemini-embedding-001
                dimensions: 768
  sheltr:
    search:
      relevance-threshold: 0.75
  ```
- `application-local.yml` (new):
  ```yaml
  spring:
    ai:
      ollama:
        base-url: http://localhost:11434
        chat:
          model: qwen2.5:3b-instruct
        embedding:
          model: nomic-embed-text
  sheltr:
    search:
      relevance-threshold: <empirically derived, see Section 5>
  ```

## 4. Models & Serving

- **Generation**: `qwen2.5:3b-instruct` — Apache-2.0 licensed (matches project license, ADR-0009),
  strong instruction-following for its size, runs acceptably on CPU-only hardware with 8–16GB
  RAM (the "modest self-host" target this spec assumes — an old laptop or a cheap VPS, not a
  GPU box).
- **Embedding**: `nomic-embed-text` — 768 dimensions, matching the existing `pgvector` column
  width, so no schema migration is needed switching from `gemini-embedding-001`.
- **Serving**: Ollama runs as a sidecar container in `infra/podman/compose.yaml`. `make up` is
  extended to wait for the Ollama healthcheck and then run `ollama pull qwen2.5:3b-instruct`
  and `ollama pull nomic-embed-text` (idempotent — a no-op after the first run, since Ollama
  caches pulled models in its volume).
- Both models are pulled from Ollama's library, which mirrors the same weights published on
  Hugging Face — satisfies the "easily available on Hugging Face" requirement while getting
  Ollama's quantization and CPU-inference handling for free.

## 5. Threshold Recalibration

ADR-0008 makes the relevance threshold a safety-critical number: too low, and unrelated
content gets cited as if relevant; too high, and answerable questions get an unnecessary
"no answer" response. The existing `0.75` was tuned against Gemini's embedding space and does
not transfer to `nomic-embed-text`'s different score distribution.

- Build a small eval fixture: hand-labeled `(question, chunk, isRelevant)` triples spanning all
  8 `DisasterType` values, drawn from the existing seed-data migration (`9779163`) plus the
  ingested FEMA caregivers guide — roughly 40–60 pairs, enough to see a clear precision/recall
  curve without becoming its own multi-week labeling project.
- A JUnit-based eval harness embeds each fixture pair under `nomic-embed-text`, computes cosine
  similarity, and sweeps candidate thresholds, reporting false-accept rate (irrelevant chunk
  scored above threshold) and false-reject rate (relevant chunk scored below threshold) at
  each point.
- Threshold selection favors minimizing false-accepts even at the cost of some false-rejects —
  consistent with ADR-0008's existing position that a wrong "I don't know" is safer than a
  wrong invented fact. The chosen value is recorded in this spec's companion implementation
  plan and in `application-local.yml`, with the sweep results kept as a checked-in fixture so
  the threshold can be re-derived if the embedding model ever changes again.

## 6. Testing Strategy

- **Unit tests** (`SpringAiEmbeddingGeneratorAdapter`/`SpringAiAnswerGeneratorAdapter` logic,
  `IngestDocumentServiceTest`, etc.) are unaffected — they exercise domain/application logic
  against fakes, never a real Spring AI bean.
- **New threshold-eval test**: runs the labeled fixture through the real `nomic-embed-text`
  model (requires a reachable Ollama instance) and asserts the chosen threshold achieves the
  target false-accept rate — a regression guard, not just a one-time calibration script.
- **New local-profile E2E test**: mirrors the existing `IngestionToRagEndToEndTest` (currently
  gated on `GEMINI_API_KEY`), gated instead on Ollama being reachable — ingests the real FEMA
  PDF, queries it via the real `Retriever`, confirms relevant results come back. Gives the
  local path the same end-to-end proof the Gemini path already has, and lets contributors
  without a Gemini key still run a full E2E test locally and in CI.
- The existing Gemini-gated E2E test is unchanged and still runs when `GEMINI_API_KEY` is set.

## 7. Error Handling & Dependencies

- New dependency: `spring-ai-ollama-spring-boot-starter` (Apache-2.0), added alongside the
  existing Gemini starter — both present on the classpath, only one active per profile.
- Adapter error handling is unchanged in shape (`AnswerGenerationFailed` on any provider
  exception) but the error message moves from `"Gemini call failed: ..."` to a provider-neutral
  `"Answer generation failed: ..."`, since the same adapter class now serves either provider.
- If Ollama is unreachable under the `local` profile (e.g. sidecar not started), Spring AI's
  autoconfigured client raises a connection exception on first call, surfaced through the
  existing `AnswerGenerationFailed`/embedding-failure paths — no new error-handling code needed,
  the existing failure path already covers "provider unreachable."

## 8. ADR Updates

- **Amend ADR-0003**: retitle to reflect that Gemini is no longer the sole permitted provider;
  record that the "Gemini only" product requirement is superseded by the open-source release
  goal, while keeping the ADR's core rule (no vendor SDK type outside `infrastructure`) intact
  — both providers must comply.
- **New ADR** (next number, 0011): records that `local` (Ollama, CPU-capable, Hugging-Face
  weights) is the default provider for the open-source release, with Gemini available as an
  explicit opt-in for users who want cloud-hosted quality/speed instead.

## 9. Global Constraints Carried Forward

- Domain packages remain framework-free (ADR-0002) — this migration touches only
  `infrastructure`/`config` code and `application-*.yml`.
- Container runtime stays Podman-only (ADR-0010) — the Ollama sidecar goes in the same
  `podman compose` file as Postgres.
- License compatibility with the project's Apache-2.0 license (ADR-0009) is a selection
  criterion for both the Ollama starter and the chosen models.
