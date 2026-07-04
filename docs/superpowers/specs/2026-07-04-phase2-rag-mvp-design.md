# Sheltr — Phase 2 (Sub-project 1): MVP RAG Loop Design

Status: Approved
Date: 2026-07-04

## 1. Scope

This spec covers the **first buildable slice of Phase 2** for the Sheltr Disaster Response
Knowledge Assistant: a working, citation-grounded RAG loop wired into the hexagonal skeleton
built in Phase 1 (`docs/superpowers/specs/2026-07-04-phase1-foundation-design.md`).

The original product brief's Phase 2 ("Spring Boot backend, RAG pipeline, ingestion
framework, authentication, observability") is itself multiple independent subsystems. This
spec deliberately narrows to just the RAG loop — a citizen can ask a question and get a
real, citation-backed answer from Gemini, grounded in real (seeded) disaster-guidance text.
Ingestion, authentication, and observability are separate sub-projects with their own specs
when their turn comes.

**In scope:**
- Semantic search only (pgvector cosine similarity) — no hybrid/keyword search, HyDE,
  multi-query retrieval, re-ranking, or context compression yet.
- Citation-grounded answer generation via Google Gemini through Spring AI.
- Confidence scoring (derived from retrieval similarity).
- Single-turn conversation persistence (`Conversation`/`Message`/`Citation` written to the
  database), but no multi-turn context carried into the generation prompt yet.
- Non-streaming responses only (`POST /conversations/{id}/ask`).
- A small seed-data + embedding-backfill mechanism to populate real, embedded test chunks,
  since the Ingestion pipeline that will normally populate `knowledge.chunks` doesn't exist
  yet.

**Explicitly out of scope for this spec** (each becomes its own spec when its phase starts):
Ingestion pipeline and connectors (FEMA/Red Cross/WHO/UNOCHA), hybrid/keyword search, HyDE,
multi-query retrieval, cross-encoder re-ranking, context compression, streaming responses
(`/ask/stream`), multi-turn conversation memory (carrying prior turns into the prompt),
JWT/OAuth2 authentication, rate limiting, observability (Micrometer/OpenTelemetry/Grafana),
Resilience4j retry/circuit-breaking, admin ingestion endpoints, RAG evaluation/hallucination
metrics tooling.

## 2. Architecture

Fills in real classes in the empty hexagonal package skeleton from Phase 1
(`knowledge`, `search`, `ai` bounded contexts — `ingestion` stays untouched this slice).
Request flow:

```
REST (ai/interfaces) → AskQuestionService (ai/application)
  → Retriever port (search/domain) → PgvectorSemanticSearchAdapter (search/infrastructure)
      — uses Gemini EmbeddingModel to embed the query, then jOOQ cosine-similarity query
        against knowledge.chunks
  → AnswerGenerator port (ai/domain) → GeminiAnswerGeneratorAdapter (ai/infrastructure)
      — uses Gemini ChatModel via Spring AI; enforces ADR-0008's relevance-threshold gate
  → ConversationRepository (ai/infrastructure, jOOQ) persists Conversation/Message/Citation
```

`KnowledgeDocumentRepository` / `ChunkRepository` (knowledge/infrastructure, jOOQ) back the
retrieval adapter's chunk lookups and the `GET /documents/{id}` / `.../citations` endpoints.

## 3. Gemini / Spring AI Wiring

- **Embedding model:** `text-embedding-004` via Spring AI's Google GenAI (Gemini)
  `EmbeddingModel` — 768-dim output, matches the existing `knowledge.chunks.embedding
  VECTOR(768)` column (no schema change).
- **Generation model:** `gemini-2.5-flash` via Spring AI's `ChatModel`, configurable via
  `application.yml`, not hardcoded.
- **Auth:** `GEMINI_API_KEY` environment variable (Google AI Studio path), consumed by Spring
  AI's autoconfiguration. No key ever appears in a committed file — only referenced by name
  (`${GEMINI_API_KEY}`) in `application.yml`.
- **ADR-0003 compliance:** all Gemini/Spring-AI-specific types stay inside
  `search/infrastructure` and `ai/infrastructure`. Domain/application code depends only on
  the `Retriever` and `AnswerGenerator` ports.

## 4. Confidence Scoring & "No Answer Available" (ADR-0008)

- Retrieval ranks chunks by cosine similarity (pgvector `<=>` operator, via jOOQ).
- **Relevance threshold** (`sheltr.search.relevance-threshold`, default `0.75`, configurable):
  if the top result's similarity is below this, `AnswerGenerator` returns `NoAnswerAvailable`
  without invoking Gemini's `ChatModel` — the "never invent guidance" rule enforced in code,
  not just prompt text.
- **Confidence level** for answers that do generate: `HIGH` if top-chunk similarity ≥ 0.90,
  `MEDIUM` if ≥ 0.75. `LOW` is defined in the domain model (`AnswerConfidence`) but is
  currently unreachable given the 0.75 gate — any answer that generates has already cleared
  0.75, so `LOW` has no path to it in this slice. This is intentional and stated explicitly
  rather than left as silent dead code; it becomes reachable if the threshold is later
  loosened (e.g. to surface a low-confidence answer instead of refusing outright).
- Both numbers are starting points, not tuned against real eval data (no RAG evaluation
  harness exists yet) — a tuning follow-up once real ingestion/usage data exists.

## 5. Seed Data & Embedding Generation

Hand-baking fake vectors into a SQL migration would be misleading — cosine similarity over
fake vectors proves nothing about real retrieval behavior. Instead:

- A Flyway migration (`V5__seed_knowledge_documents.sql`) seeds `knowledge.documents` and
  `knowledge.chunks` with ~30-40 rows of real, short disaster-guidance text (3-5 chunks per
  disaster type across all 8 types: earthquake, flood, hurricane, tornado, tsunami, wildfire,
  extreme heat, winter storm — covering common questions like water safety, shelter, first
  aid, evacuation). `embedding` is left `NULL` (the column is already nullable).
- A **`ChunkEmbeddingBackfillService`** (Knowledge context, `application` layer) finds chunks
  where `embedding IS NULL`, calls the Gemini `EmbeddingModel`, writes the vectors back.
  Exposed as `make seed-embeddings` (a small Spring Boot CLI-style runner), not auto-triggered
  on every app boot or test run — ordinary `mvn test` / app startup must not require a live
  Gemini call just to compile/boot.
- This deliberately previews the "Embedding Generator" stage of the future Ingestion pipeline
  described in the original product brief's pipeline diagram (Downloader → Content Extractor
  → Cleaner → Metadata Extractor → Chunker → Embedding Generator → Vector Store) — the same
  capability gets reused once real ingestion exists, rather than being a throwaway seeding
  hack specific to this slice.

## 6. API Surface (this slice)

Real controller implementations for:
- `POST /conversations` — create a new conversation
- `GET /conversations/{id}` — read conversation history
- `POST /conversations/{id}/ask` — the core RAG loop (non-streaming)
- `GET /documents/{id}` — document metadata (citation drill-down)
- `GET /documents/{id}/citations` — citations referencing a document

`POST /conversations/{id}/ask/stream` and all `/admin/ingestion/*` endpoints remain
undefined (no controller) — already documented in the Phase 1 OpenAPI spec as future paths;
not implementing them yet is consistent with Ingestion not existing yet.

## 7. Testing Strategy

- **Domain unit tests** (plain JUnit, no Spring context): `AnswerGenerator`'s threshold-gating
  logic and confidence-band mapping, tested against hand-written fake `Retriever`/ports — no
  I/O, no Gemini dependency, fast.
- **Adapter integration tests** (Testcontainers Postgres+pgvector, same pattern as Phase 1):
  real cosine-similarity SQL query correctness, jOOQ repository round-trips.
- **Gemini adapter tests**: real API calls, gated behind
  `@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")` — skip gracefully
  without a key present, run for real when one is set (the reference dev environment has one).
- **End-to-end test**: full Spring Boot test, Testcontainers-seeded DB (fixture chunks with
  real backfilled embeddings) + real Gemini calls (same env-var gate), asserting a
  citation-bearing answer for an in-corpus question and a `NoAnswerAvailable` result for a
  clearly out-of-corpus question — proves ADR-0008's threshold gate end-to-end, not just in
  isolated unit tests.

## 8. Error Handling

Gemini failures (timeout, rate limit, malformed response) are caught at the adapter boundary
and mapped to a domain-level `AnswerGenerationFailed` outcome — distinct from
`NoAnswerAvailable` — surfaced as a clean 5xx REST response with no leaked stack traces. No
Resilience4j retry/circuit-breaking yet (a later production-hardening phase per the original
roadmap); this slice uses a straightforward try/catch translation at the adapter.

## 9. Global Constraints Carried Forward from Phase 1

- Domain packages (`knowledge/domain`, `search/domain`, `ai/domain`) have zero Spring/jOOQ/
  Gemini-SDK imports (ADR-0002).
- No Gemini SDK type outside `infrastructure` packages (ADR-0003).
- No secrets in source code — `GEMINI_API_KEY` only ever read from environment (ADR/global
  constraint carried from Phase 1; reinforced here given a real key is in play this phase).
- Container runtime: Podman only (ADR-0010) — all local dev/test instructions in this spec's
  implementing plan use `podman compose` / `make up` / `make migrate`, consistent with Phase 1.
