# Native Lumina Chat UI — Design

Superseded in part by ADR-0015.

Status: Approved
Date: 2026-08-24

Depends on `docs/superpowers/specs/2026-08-07-spring-boot-4-upgrade-design.md` being
implemented first (Lumina's Spring modules require Spring Boot 4.1.0 / Spring AI 2.0.0).
Supersedes the Proposed draft `docs/superpowers/specs/2026-08-07-lumina-chat-ui-design.md`.

## 1. Scope

Sheltr has a working RAG backend (Phases 1–3, local Ollama default) and no UI — only the
REST API in `docs/openapi/openapi.yaml`. This spec adds a citizen-facing chat page by
embedding [Lumina](https://github.com/wosslabs/lumina) 1.0.0 in the same JVM as the backend.

A citizen can ask a disaster-response question, see the answer stream token by token, see
its citations (source title, URL, page) and a text confidence indicator, and have the
conversation persist and replay on refresh. Conversations started through Lumina are stored
in the same `ai` schema / `Conversation` / `Message` model as REST.

**In scope:**

- In-process Lumina (Path C in Lumina's `DEVELOPER_GUIDE.md`): `lumina-spring-boot-starter` +
  `lumina-spring-ai`, Maven coordinates `io.github.wosslabs`, version `1.0.0`.
- UI on `lumina.port=8090`; existing Spring MVC REST API stays on `server.port=8080`.
- Shared prompt / citation / confidence construction via a new `AnswerContextBuilder`.
- Streaming answers through Lumina's auto-configured `io.lumina.ai.ChatClient` (backed by
  the profile's existing `ChatModel` bean — `local` or `gemini`).
- README note that the chat UI is `http://127.0.0.1:8090/` (opening `:8080` shows
  disconnected Lumina static HTML when `spring-boot-starter-web` is on the classpath).

**Out of scope** (separate specs; A and D on the release track, or later phases):

- Auth / login; admin-endpoint lock-down; URL allowlisting on ingestion.
- Rate limiting, request-size caps, hosted bind/credentials hardening.
- Disclaimer copy, real-corpus swap, CI, community files (`CONTRIBUTING`, `SECURITY.md`).
- Disaster-type filter in the UI, document browser, admin curation UI, mobile.
- REST `POST /conversations/{id}/ask/stream` (still unimplemented; do not add it here).
- Multi-turn memory in the generation prompt (UI replays history; generation uses only the
  current question, same as REST today).
- Extending the `AnswerGenerator` port with a streaming method.

## 2. Architecture

Two callers, one shared prompt builder, REST port unchanged:

```
REST  ConversationController → AskQuestionService
        → Retriever → RelevanceGate
        → AnswerGenerator.generate()          # blocking, unchanged port
             → AnswerContextBuilder
             → ChatModel.call(prompt)

Lumina LuminaChatApp (LuminaApp bean, :8090)
        → Retriever → RelevanceGate           # duplicated ~15-line loop
        → AnswerContextBuilder                # same as REST
        → io.lumina.ai.ChatClient.stream()    # auto-configured from ChatModel
        → ConversationRepository.save()
```

Lumina shares the Spring context with the backend. No HTTP hop, no second provider
configuration. Lumina 1.0.0's auto-configuration can miss the `ChatModel` bean because
`LuminaSpringAiAutoConfiguration` has no `@AutoConfigureAfter` relationship with Spring AI's
chat auto-configuration. Sheltr therefore registers an `@Bean io.lumina.ai.ChatClient` as
`SpringAiChatClient(ChatClient.create(chatModel))`, guarded by `@ConditionalOnMissingBean`.
Remove this workaround when Lumina adds `@AutoConfigureAfter` for Spring AI chat
auto-configuration (or equivalent) and the bean is unused.

The retrieve → gate → persist loop is duplicated in `LuminaChatApp` rather than routed
through `AskQuestionService`, because that service is blocking and returns a finished
`Message`. A third caller would be the trigger to extract a shared orchestrator.

## 3. Components

| Unit | Role |
|---|---|
| `ai/application/AnswerContextBuilder` | **New.** `buildContext(SearchQuery, List<RankedResult>) → AnswerContext`. Owns prompt text, citation list (via `KnowledgeDocumentRepository`), and `AnswerConfidence.fromSimilarity(topScore)`. Extracted from `SpringAiAnswerGeneratorAdapter`. |
| `ai/domain/NoAnswerAvailable` | Add a public constant for the refuse text currently hardcoded in `AskQuestionService`: `"I don't have enough reliable information from trusted sources to answer that question."` Both REST and Lumina use this constant. |
| `ai/infrastructure/SpringAiAnswerGeneratorAdapter` | Calls `AnswerContextBuilder`, then `chatModel.call(context.prompt())`. Constructor takes `ChatModel` + `AnswerContextBuilder` (replacing the direct `KnowledgeDocumentRepository` dependency). |
| `ai/interfaces/LuminaChatApp` | **New.** Implements `LuminaApp`. Chat page: session conversation, replay, retrieve/gate, stream or refuse, persist. Injects `ConversationRepository`, `Retriever`, `AnswerContextBuilder`, `io.lumina.ai.ChatClient`, and `sheltr.search.relevance-threshold`. `TOP_K` is `5`, same as `AskQuestionService`. |
| `ai/config/LuminaChatAppConfig` | **New.** `@Bean LuminaApp` returning `LuminaChatApp`, plus the conditional Lumina 1.0.0 `ChatClient` workaround described above. |
| `ai/config/AnswerContextBuilderConfig` | **New.** `@Bean AnswerContextBuilder` from `KnowledgeDocumentRepository`. |
| `apps/backend/pom.xml` | Add `lumina-spring-boot-starter` and `lumina-spring-ai`, both `io.github.wosslabs:1.0.0`. |
| `application.yml` | `lumina.port: 8090` (all profiles). |

`AnswerContext` is a simple record in `ai/application`: `prompt: String`, `citations: List<Citation>`, `confidence: AnswerConfidence`. Prompt content is the existing adapter string: disaster-response instruction, retrieved chunk texts, then the question. Do not split system/user roles in this slice — REST's `chatModel.call(String)` does not, and Lumina's `ChatClient.stream(String)` sends that same string as the user message.

Confidence has no Lumina widget. After a successful streamed answer, render the level (`LOW` / `MEDIUM` / `HIGH`) as plain text next to the answer. Do not use `ui.usage` (that widget is tokens/cost/latency).

## 4. Data flow (per Lumina UI run)

1. Read `conversationId` from `ui.state()`. If absent: create a `Conversation` with
   `userRef=null` (same as REST `POST /conversations`) and store the id in session state.
   One conversation per browser session.
2. Reload the `Conversation` and replay: `ui.user(...)` / `ui.ai(...)` per existing
   `Message`, in order. For assistant messages that have citations, list each source
   title and URL as text under that answer. Do not call `ui.ragSources` on replay —
   that widget is for the live turn. Do not call `ui.citation`: `Citation.pageNumber` is
   always empty today (the adapter passes `null`), and this slice does not start
   populating it.
3. `ui.chatInput()`. If the submitted text is null or blank, stop — do not retrieve or
   call the model (`SearchQuery` / `MessageContent` already reject blank text).
4. Append a USER `Message`, `retriever.retrieve(query, 5)`, then
   `RelevanceGate.passesThreshold(results, threshold)`.
5. **Below threshold:** `ui.ai(NoAnswerAvailable.MESSAGE)`. No `ragSources`. Persist the
   fallback assistant `Message` with empty citations and null confidence, matching REST.
6. **Above threshold:** `AnswerContextBuilder.buildContext(...)` → `ui.ragSources` as
   `List<Map<String, Object>>` with keys `title` (citation `sourceTitle`) and `url`
   (citation `sourceUrl`) → `String reply = ui.ai(chatClient.stream(context.prompt()))` →
   persist ASSISTANT `Message` with `reply`, `context.citations()`, `context.confidence()`,
   and retrieved chunk ids. Render confidence as text.

The page calls `ui.title("Sheltr")` once per run.

## 5. Error handling

- If `ui.ai(chatClient.stream(...))` throws `LuminaException` (upstream Ollama/Gemini
  failure): persist the USER message only; do not persist a fabricated assistant reply;
  surface a session-local friendly error; do not crash the render loop. Same outcome as
  REST's `AnswerGenerationFailed`.
- Retrieval or database failure: let it propagate. Lumina isolates it to that session.
- Isolation is structural: `conversationId` lives only in per-session `ui.state()`.

## 6. Testing

- `AnswerContextBuilderTest` — unit, no Spring. Prompt contains the question and chunk
  text; citations take title/URL from the document repository; confidence matches
  `AnswerConfidence.fromSimilarity` on the top score; missing documents are skipped
  (same as today's adapter loop).
- `SpringAiAnswerGeneratorAdapterTest` — assertions unchanged. Construction may pass
  `new AnswerContextBuilder(documentRepository)` instead of the repository directly.
  If assertions change, the extract changed behavior and is wrong.
- Existing REST e2e (`LocalRagLoopEndToEndTest`, `RagLoopEndToEndTest`) must still pass.
- `LocalLuminaChatEndToEndTest` — `@ActiveProfiles("local")`,
  `@EnabledIfEnvironmentVariable(named = "OLLAMA_AVAILABLE", matches = "true")`,
  Testcontainers Postgres, `SpringBootTest`. Drive Lumina's WebSocket the same way
  Lumina's `HelloAiAppTest` / `StreamingChatAppTest` do (set `lumina.port=0` for an
  ephemeral port). Submit a question that the seed corpus can answer, assert a streamed
  citation-bearing reply, assert rows in `ai.conversations` / `ai.messages` via
  `ConversationRepository`.
- `LuminaChatEndToEndTest` — same shape, `@EnabledIfEnvironmentVariable(named =
  "GEMINI_API_KEY", matches = ".+")`, matching `RagLoopEndToEndTest`.
- Refuse path: no new e2e. `RelevanceGateTest` covers the gate; persist-of-fallback is
  the same `Message` shape REST already tests through `AskQuestionServiceTest`.

## 7. Gate / definition of done

- Chat UI at `http://127.0.0.1:8090/` streams a citation-backed answer against seed data
  under the `local` profile.
- REST `/api/v1/conversations/{id}/ask` behavior unchanged (existing tests green).
- Refuse text is the `NoAnswerAvailable` constant in both paths.
- Both local and Gemini Lumina e2e tests exist and are gated like the REST pair.

## 8. Alternatives considered

- **Standalone Lumina (`lumina-web`) calling REST.** No Boot 4 coupling. Extra hop, two
  deploys, prompt/citation drift. Rejected; native in-process integration is the product
  direction (why the Boot 4 spec exists).
- **Stream via a new `AnswerGenerator` method.** No second streaming REST caller yet.
  Rejected until `/ask/stream` is implemented.
- **Blocking Lumina via `AskQuestionService` + `ui.ai(String)`.** Simpler, no streaming.
  Rejected; token streaming is the UX this slice exists to ship.
- **Rely only on Lumina's auto-configured `ChatClient`.** Preferred once Lumina orders its
  auto-configuration after Spring AI chat auto-configuration. Rejected for Lumina 1.0.0
  because its `@ConditionalOnBean(ChatModel)` can be evaluated before the `ChatModel` exists;
  keep Sheltr's conditional workaround until the upstream ordering is fixed and it is unused.
