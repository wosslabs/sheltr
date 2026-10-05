# Sheltr — Phase 0 Hardening Design

Superseded in part by ADR-0015.

Status: Approved in direction (2026-10-04). Rulings below are the controller's decisions where the approved list left a choice open.
Date: 2026-10-04

## 1. Purpose

Phase 0 closes the findings that block any public exposure of the Sheltr backend. It changes no answer logic, retrieval, corpus or eval behaviour. Existing working behaviour is preserved except where a finding requires a change in an error response or an access rule.

In scope, from the architectural assessment:

| ID | Finding | Priority |
|---|---|---|
| X1 | Admin ingestion endpoints are unauthenticated; the fetch URL is caller-supplied (outbound request surface) | Critical |
| X2 | `/ask` is anonymous, unlimited, and each call pays for an LLM call | Critical |
| X3 | External HTTP calls have no timeouts | High |
| S1 | No bean validation, no global error handler, unbounded question length | High |

Out of scope (later phases): async ingestion (X4), conversation concurrency (X5), pagination (X6), Lumina orchestration move (A2), ArchUnit (A3), metrics (O1), citation policy (R1), CI.

## 2. Decisions

### 2.1 Admin authentication (X1)

- Add `spring-boot-starter-security`. Justification: the only defensible way to protect the admin surface is a filter chain; hand-rolled checks would be weaker.
- `/api/v1/admin/**` requires HTTP Basic authentication with role `ADMIN`. All other `/api/**` routes stay public: they are the citizen API.
- Credentials come from the environment in every profile, including local: `SHELTR_ADMIN_PASSWORD` (plaintext) or `SHELTR_ADMIN_PASSWORD_HASH` (bcrypt), never both. Neither set fails startup, naming both variables. No admin password is ever written to a YAML file. Carve-out (ruling): the database credential in `application.yml` uses `${SHELTR_DB_PASSWORD:sheltr}`, whose default is the local Postgres container credential only; it is not the admin credential and is documented as such.
- Ruling (plaintext handling): a plaintext `SHELTR_ADMIN_PASSWORD` is bcrypt-encoded in memory at startup and the plaintext is discarded; a hash is used as given. Plaintext is never logged.
- Local developers export a value (documented in the README). Tests supply their own password through test properties.
- Responses for unauthenticated admin calls are `401`; authenticated but not admin is `403`.
- The Lumina UI on port 8090 is a separate server and is not covered by this filter chain. The Lumina chat is a separate adapter, not the REST `/ask` endpoint. It shares the daily cap (`DailyAskCap`, `sheltr.limits.max-asks-per-day`) and is not covered by the per-minute ask limit. It exposes only the chat UI; no admin action is reachable from it. Recorded as a residual risk: a future Lumina admin page must add its own authorisation.

### 2.2 Ingestion fetch allowlist (X1, second part)

- `sheltr.ingestion.allowed-hosts` lists hostnames the connector may fetch. Empty means deny all.
- `POST /api/v1/admin/ingestion/runs` with a host outside the list returns `400` with a clear message. The check runs before any network call.
- Redirects are not followed at all: the connector uses `HttpClient.Redirect.NEVER` and fails on any 3xx. Ruling: no redirects, because approved sources publish the final URL directly, and following redirects would let an allowed host send the fetch elsewhere.
- Local and test profiles list `www.ready.gov` and the other approved hosts used by existing tests.

### 2.3 Rate limiting and daily cap (X2)

- Per-client-IP fixed-window limit on `POST /api/v1/conversations/*/ask`: default 10 requests per minute, configurable `sheltr.limits.ask-per-minute`. Exceeding returns `429` with `Retry-After`.
- Implemented as a small servlet filter with a bounded in-memory map. Ruling: no third-party rate-limit library. Reason: one endpoint, one process today. The map is bounded (evicts windows older than one minute, hard cap on entries) so memory cannot grow without limit. When the app runs behind a proxy, the proxy's limit is the primary control; this filter is the backstop. The client IP is taken from the socket address only. `X-Forwarded-For` is not trusted unless a trusted-proxy setting is added later.
- Daily cost cap: `sheltr.limits.max-asks-per-day` (default 1000). Counted from `ai.messages` rows with role `USER` created since midnight UTC. Exceeding returns `429`. The count uses one indexed query, so it survives restarts. Ruling: database count, not in-memory, so the cap holds across restarts and instances.
- The cap is checked before the LLM call, so a refused request costs nothing.

### 2.4 Timeouts (X3)

- `HttpPdfConnector`: connect timeout 10 s, request timeout 60 s, redirect policy `NEVER`.
- Spring AI chat and embedding clients: set the connection and read timeouts through their configuration properties, after the implementer verifies the exact property names against the Spring AI 2.0 jars in the local Maven repository. If a provider exposes no timeout property, the plan records that as a residual risk and does not wrap the call in a custom executor.
- Timeouts are configured in `application.yml` with a comment, not hard-coded.

### 2.5 Validation and error handling (S1)

- Add `spring-boot-starter-validation`.
- `AskRequestDto.question`: `@NotBlank`, `@Size(max = 2000)`. `activeHazards`: `@Size(max = 12)`.
- `TriggerIngestionRequestDto.sourceUri`: `@NotBlank`. Existing field-level checks in `IngestionController` stay as domain validation.
- One `@RestControllerAdvice` in `com.sheltr.interfaces` (shared across contexts) returns a single error body: `{ "error": "<code>", "message": "<safe message>", "timestamp": "<instant>" }`.
  - Validation failure → `400`, message names the invalid field.
  - Not found → `404`.
  - Rate or cap exceeded → `429`.
  - Generation failure → `502`. Stale embeddings → `503`.
  - Anything else → `500` with a generic message. The stack trace and exception message are logged, never returned.
- The per-controller `@ExceptionHandler` methods move into the advice.

## 3. Architecture placement

- Security configuration, the rate-limit filter and the advice are interface-layer or configuration concerns. They sit in `com.sheltr.interfaces` and `com.sheltr.config`, not in the domain.
- The daily cap is an application concern. `AskQuestionService` calls a new port method `ConversationRepository.countUserMessagesSince(Instant)` before the pipeline runs. The port is in `ai.domain`, implemented in `ai.infrastructure`. The domain stays free of Spring.
- The ingestion host allowlist is checked in `IngestDocumentService` through a configuration-supplied `SourcePolicy` port, so the application layer owns the rule and tests need no network.

## 4. Testing

- Unit: allowlist decision, daily-cap decision, rate-window logic (including eviction and the hard cap), advice mapping for each exception type.
- Integration (MockMvc or the existing `TestRestTemplate` style): 401 without credentials, 403 with a non-admin user, 200 with admin; `/ask` 400 for blank and over-length questions; 429 after the per-minute limit; 429 after the daily cap; 400 for a disallowed host with no outbound call (asserted by a fake connector).
- Existing admin integration tests are updated to send admin credentials. No assertion is weakened.
- Gated tests are unchanged.

## 5. Documentation

- ADR-0014: admin authentication, fetch allowlist, and the decision not to add a rate-limit library.
- README: required environment variables (`SHELTR_ADMIN_PASSWORD` or `SHELTR_ADMIN_PASSWORD_HASH`), the local development password, and the limit settings.
- `docs/openapi/openapi.yaml`: add 400, 401, 403, 429 responses for the affected operations.

## 6. Residual risks after Phase 0

- No edge proxy exists in the repository; the in-app limiter is a backstop, not the primary control.
- The Lumina server on port 8090 has no authorisation layer of its own.
- Spring AI provider timeouts depend on what the provider exposes.
- Admin credentials are a single shared account; there are no per-person identities.

### Recorded residuals (Task 8)

Recorded in ADR-0014 as well. Items 1 to 11 are the complete residual list after Phase 0.

1. The Spring `Environment` property source still holds the admin plaintext (not a bean; documented in `AdminCredentials.load` Javadoc).
2. An authenticated admin calling a denied actuator path gets `403` with "Admin role required." (cosmetic).
3. No edge proxy exists in the repository; the in-app limiter is a backstop and is per instance, so N replicas give N budgets. Behind a proxy, the proxy limit is primary. The limiter trusts the socket address only.
4. The in-app limiter evicts nothing live: at 10000 tracked clients it refuses new clients until windows expire (fail closed).
5. The daily cap is a soft cap under concurrency (check-then-act), and it counts failed generations.
6. Gemini (Spring AI google-genai) has no application-level request timeout; Ollama is bounded by `spring.http.clients.read-timeout` (120 s), which applies to every Boot RestClient.
7. The `HttpRequest` timeout bounds the wait for response headers, not a slow body stream.
8. The Lumina chat on port 8090 has no authorisation layer of its own and is not covered by the per-minute ask limit (the daily cap does apply).
9. The local development admin password must be exported (`SHELTR_ADMIN_PASSWORD`) before running the app; there is no default.
10. The database default password in `application.yml` is the local Postgres container credential (spec §2.1 carve-out).
11. Gated tests (`GEMINI_API_KEY`, `OLLAMA_AVAILABLE`, Docker) are skipped in CI-less runs; Phase 0 endpoints were verified by the non-gated suite only for the Gemini-gated admin calls.
