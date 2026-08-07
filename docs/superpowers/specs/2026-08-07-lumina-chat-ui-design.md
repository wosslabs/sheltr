# Native Lumina Chat UI — Design

## Status

Proposed. Depends on
`docs/superpowers/specs/2026-08-07-spring-boot-4-upgrade-design.md` being merged first
(Lumina's Spring modules require Spring Boot 4.1.0 / Spring AI 2.0.0).

## Context

Sheltr has a working RAG backend (Phase 1-3, local/CPU model migration) but no UI —
only a REST API (`docs/openapi/openapi.yaml`) and Testcontainers-driven tests exercise
it. [Lumina](https://github.com/wosslabs/lumina) is a server-driven Java UI framework
(Streamlit-like: no HTML/CSS/JS in application code) with built-in widgets purpose-built
for RAG chat: `ui.chatInput`, `ui.ai(TokenStream)`, `ui.ragSources`, `ui.citation`,
`ui.usage` (see Lumina's `docs/AI_GUIDE.md`).

This is the path to an MVP1 release: a working, citable, citizen-facing chat UI on top
of the existing backend, before mobile app or admin curation UI work begins.

Lumina runs embedded in the same JVM as the existing Spring Boot app (Path C in
Lumina's `DEVELOPER_GUIDE.md`: `lumina-spring-boot-starter` + `lumina-spring-ai`), on
its own port (`lumina.port=8090`, separate from the existing REST API's `:8080`) —
sharing the same application context, so it calls Sheltr's existing beans in-process.
No HTTP hop, no duplicated provider configuration.

## Goals

- A citizen can ask a disaster-response question, see the answer stream in token by
  token, see its citations (source title/URL/page) and a confidence indicator, and
  have the conversation persist and replay on refresh — all through Lumina, all backed
  by Sheltr's existing retrieval, citation-grounding (ADR-0008), and persistence logic.
- Conversations started through the Lumina UI are indistinguishable in storage from
  ones started via the REST API — same `ai` schema, same `Conversation`/`Message`
  domain model, visible to any future admin-curation UI the same way.

## Non-Goals (MVP1)

- No disaster-type filter dropdown (the REST API's `AskRequest.disasterTypes` exists
  but the chat UI won't expose it yet).
- No document browsing / admin curation surfaces — that's the separate, not-yet-scoped
  `apps/admin-web` phase.
- No changes to the REST API or its existing behavior (see "Key design decision"
  below) — this is additive.
- No auth/login gate — matches the current unauthenticated stance of the rest of the
  system (see `docs/openapi/openapi.yaml`'s admin-endpoints note); this is a public,
  citizen-facing tool.

## Key design decision: don't touch the `AnswerGenerator` port

The REST path (`ConversationController` → `AskQuestionService` →
`AnswerGenerator.generate()`, blocking) stays exactly as it is today — zero regression
risk to the existing, already-tested flow. The Lumina path is new, additive code that
reuses the same underlying pieces (`Retriever`, `RelevanceGate`, `ConversationRepository`,
the same `ChatModel` bean) without modifying or wrapping the existing port.

The one piece of *shared* logic extracted out is prompt-building and
citation/confidence construction — currently private code inside
`SpringAiAnswerGeneratorAdapter` — because letting the REST path and the new streaming
Lumina path each independently compute "how do chunks become a prompt" would let the
two silently drift apart over time. Everything else about the Lumina path
(orchestration: retrieve → gate → branch → persist) is small enough (~15 lines) that
it's deliberately duplicated once rather than forced into a shared abstraction across
a blocking caller and a streaming caller — revisit only if a third caller appears.

## Components

| File | Change |
|---|---|
| `ai/application/AnswerContextBuilder.java` | **New.** Extracted from `SpringAiAnswerGeneratorAdapter`'s private `buildPrompt` + citation loop. `buildContext(SearchQuery, List<RankedResult>) → AnswerContext(prompt, citations, confidence)`. |
| `ai/infrastructure/SpringAiAnswerGeneratorAdapter.java` | **Refactored, not behavior-changed.** Calls `AnswerContextBuilder` instead of its own private logic, then `chatModel.call(context.prompt())`. Existing `SpringAiAnswerGeneratorAdapterTest` should pass unmodified. |
| `ai/interfaces/LuminaChatApp.java` | **New.** `@Bean LuminaApp` — the chat page (see Data flow below). |
| `ai/config/LuminaChatClientConfig.java` | **New.** `@Bean ChatClient` wrapping the existing profile-configured `ChatModel` bean (`ChatClient.builder(chatModel).build()`) — one bean, no new provider wiring, works for whichever profile (`local`/`gemini`) is active. |
| `apps/backend/pom.xml` | Add `lumina-spring-boot-starter` + `lumina-spring-ai`. |
| `application.yml` | Add `lumina.port=8090` (shared across profiles — Lumina itself is provider-agnostic; only the underlying `ChatModel`/`ChatClient` bean varies by profile). |

## Data flow

**Per Lumina UI run:**

1. Read `conversationId` from `ui.state()`. If absent: call `conversationRepository`
   to start a new `Conversation` (same effect as REST's `startConversation`) and store
   the id in session state. One conversation per browser session — never shared
   global state, so concurrent users never see each other's conversations or errors.
2. Reload the `Conversation` from `conversationRepository` and replay it:
   `ui.user(...)` / `ui.ai(...)` per existing `Message`, in order.
3. `ui.chatInput()` → if the user submitted a question: append a USER `Message` to the
   conversation, `retriever.retrieve(query, TOP_K)`, then
   `RelevanceGate.passesThreshold(results, threshold)`.
4. **Below threshold (refuse):** `ui.ai(fallbackText)` — the same fallback text
   `AskQuestionService` already uses ("I don't have enough reliable information from
   trusted sources to answer that question."). No `ragSources` shown. Persist the
   fallback assistant `Message` (no citations, no confidence) via
   `conversationRepository.save(...)`.
5. **Above threshold (answer):** `AnswerContextBuilder.buildContext(query, results)` →
   `ui.ragSources(...)` rendered from `context.citations()` → `String reply =
   ui.ai(springAiChatClient.stream(context.prompt()))` (streams token-by-token to the
   browser, returns the full accumulated text) → build and persist the assistant
   `Message` from `reply` + `context.citations()` + `context.confidence()`.

Confidence display: Lumina's widget table (`AI_GUIDE.md`) doesn't list a dedicated
"confidence badge" widget (`ui.usage` is for tokens/cost/latency, not this). Treated as
an implementation detail to resolve against Lumina's actual widget reference during the
implementation plan — worst case, a plain text/markdown rendering of the confidence
level next to the answer.

## Error handling

- `ui.ai(springAiChatClient.stream(...))` throws `LuminaException` if the upstream
  `Flux` errors (Ollama/Gemini call failure) — the same failure mode
  `AnswerGenerationFailed` already models on the REST path. Catch it: persist the USER
  message only (the question was real; don't fabricate an assistant reply), surface a
  friendly error in the UI for that session, and don't crash the render loop.
- Retrieval failure (e.g. database unavailable): let it propagate. Lumina's per-session
  render loop isolates the failure to that one user's session, the same isolation
  Spring MVC already gives the REST path per-request. No special handling needed.
- Multi-user isolation is structural, not defensive: `conversationId` lives in
  Lumina's per-session `ui.state()`, so there is no shared mutable state between users
  to reason about.

## Testing

- `AnswerContextBuilderTest` — new unit test for the extracted logic (prompt shape,
  citation mapping, confidence calculation). Pure, no Spring context.
- `SpringAiAnswerGeneratorAdapterTest` (existing) — must pass unmodified after the
  refactor. If it doesn't, that's a signal the extraction changed behavior, not just
  location, and the refactor is wrong.
- `LuminaChatEndToEndTest` — new. Mirrors the existing
  `LocalRagLoopEndToEndTest`/`RagLoopEndToEndTest` pattern (real Ollama or Gemini,
  Testcontainers Postgres), but drives Lumina's actual WebSocket protocol
  (`LuminaServer` + a raw `java.net.http.WebSocket` client), the same way Lumina's own
  `HelloAiAppTest` does: submit a chat message over the socket, assert a patch event
  containing citation-bearing answer text comes back, and confirm the row landed in
  `ai.conversations`/`ai.messages` via `conversationRepository`. Gated the same way as
  the existing local/gemini e2e tests (`OLLAMA_AVAILABLE=true` / `GEMINI_API_KEY` set).
- No new test needed for the refuse path beyond what `RelevanceGateTest` /
  `RelevanceThresholdEvalTest` already cover — the gate itself doesn't change.

## Alternatives considered

- **Standalone Lumina app (`lumina-web`, Path A) calling Sheltr's REST API over HTTP.**
  This was the first option explored — no version coupling at all, since `lumina-web`
  alone has no Spring Boot/Spring AI version requirement. Rejected per explicit product
  direction in favor of a native, in-process integration (this design), which is why
  the Spring Boot 4.1/Spring AI 2.0 upgrade (previous spec) exists at all.
- **Extend the `AnswerGenerator` port with a streaming method**, used by both the REST
  path and the Lumina path. Rejected for now: the REST API has no streaming endpoint
  today (`/ask/stream` is declared in the OpenAPI spec but never implemented), so
  there is no second caller yet to justify widening the port's contract. Revisit if/when
  the REST API gets real SSE streaming.
- **Blocking (non-streaming) Lumina answers**, calling the existing `AskQuestionService`
  directly and rendering with `ui.ai(String)`. Simpler (no new `ChatContextBuilder`
  extraction needed), but rejected in favor of streaming per product direction —
  matches Lumina's intended RAG chat pattern and the token-by-token UX it's built for.
