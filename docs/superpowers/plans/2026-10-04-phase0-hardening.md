# Phase 0 Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Phase 0 findings (admin auth, fetch allowlist, ask rate limit and daily cap, external timeouts, validation and error handling) without changing answer behaviour.

**Architecture:** Security filter chain and servlet rate-limit filter in the interface layer. Validation and a single `@RestControllerAdvice` in the interface layer. Fetch allowlist and daily cap as application-layer rules behind ports. Timeouts in the infrastructure adapters and configuration.

**Tech Stack:** Spring Boot 4.1.0, Spring Security (via `spring-boot-starter-security`), Spring validation (`spring-boot-starter-validation`), Java 25, JUnit 5, AssertJ, Mockito, Testcontainers, MockMvc (`spring-boot-starter-webmvc-test` already present).

**Spec:** `docs/superpowers/specs/2026-10-04-phase0-hardening-design.md`

## Global Constraints

- Admin routes `/api/v1/admin/**` require HTTP Basic with role `ADMIN`. Unauthenticated → 401, authenticated non-admin → 403.
- `sheltr.security.admin.password` (`{noop}` or bcrypt) comes from `SHELTR_ADMIN_PASSWORD` or `SHELTR_ADMIN_PASSWORD_HASH`, never both. Non-local profile without either → startup failure.
- The local profile alone provides a fixed development credential, in `application-local.yml`.
- Fetch allowlist: `sheltr.ingestion.allowed-hosts`; empty denies all; checked before any network call.
- HTTP connector: connect timeout 10 s, request timeout 60 s, `Redirect.NEVER`.
- Rate limit: `sheltr.limits.ask-per-minute` (default 10) per socket remote address, 429 with `Retry-After`.
- Daily cap: `sheltr.limits.max-asks-per-day` (default 1000), counted from `ai.messages` role `USER` since midnight UTC; checked before the LLM call; 429 when exceeded.
- Error body everywhere: `{ "error": "<code>", "message": "<safe text>", "timestamp": "<ISO-8601>" }`. Stack traces and raw exception messages are never returned.
- Domain packages must not import Spring or security classes.
- Commit trailer: `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.
- Maven from `apps/backend`: `env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD -u GEMINI_API_KEY mvn -q ...`

## Review Focus

1. Admin route reached without credentials returns 401 and never reaches the controller. Pinned in Task 4.
2. Ingestion request for a host outside the allowlist returns 400 and performs no network call (fake connector counts calls). Pinned in Task 5.
3. Redirect from an allowed host is refused, not followed. Pinned in Task 3.
4. Ask with a question of 2001 characters returns 400 before any retrieval. Pinned in Task 2.
5. Per-minute limit and daily cap both return 429 and neither calls the LLM. Pinned in Tasks 6 and 7.

## File structure

- Create `apps/backend/src/main/java/com/sheltr/config/SecurityConfig.java` — filter chain, user details from properties.
- Create `apps/backend/src/main/java/com/sheltr/config/SecurityProperties.java` — `@ConfigurationProperties("sheltr.security.admin")` record with fail-fast validation.
- Create `apps/backend/src/main/java/com/sheltr/config/LimitProperties.java` — `@ConfigurationProperties("sheltr.limits")`.
- Create `apps/backend/src/main/java/com/sheltr/config/IngestionProperties.java` — `@ConfigurationProperties("sheltr.ingestion")` with `allowedHosts`.
- Create `apps/backend/src/main/java/com/sheltr/interfaces/ApiErrorAdvice.java` and `ApiError.java` — the single advice and error record.
- Create `apps/backend/src/main/java/com/sheltr/interfaces/AskRateLimitFilter.java` and `FixedWindowLimiter.java`.
- Create `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourcePolicy.java` — port: `boolean allows(String sourceUri)`.
- Create `apps/backend/src/main/java/com/sheltr/ingestion/config/HostAllowlistSourcePolicy.java` — implementation.
- Create `apps/backend/src/main/java/com/sheltr/ai/domain/AskQuota.java` — pure rule: `boolean allows(long askedToday, int maxPerDay)`.
- Modify `apps/backend/src/main/java/com/sheltr/ai/domain/ConversationRepository.java` — add `long countUserMessagesSince(Instant since)`.
- Modify `apps/backend/src/main/java/com/sheltr/ai/infrastructure/JooqConversationRepository.java` — implement the count.
- Modify `apps/backend/src/main/java/com/sheltr/ai/application/AskQuestionService.java` — quota check before the pipeline.
- Modify `apps/backend/src/main/java/com/sheltr/ai/interfaces/AskRequestDto.java`, `ConversationController.java` — validation, remove local exception handlers.
- Modify `apps/backend/src/main/java/com/sheltr/ingestion/interfaces/TriggerIngestionRequestDto.java`, `IngestionController.java`.
- Modify `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/HttpPdfConnector.java` — timeouts, redirect policy.
- Modify `apps/backend/src/main/resources/application.yml`, `application-local.yml`, `application-gemini.yml`.
- Modify `apps/backend/pom.xml` — two starters.
- Create `docs/adr/0014-admin-auth-and-request-limits.md`; modify `README.md`, `docs/openapi/openapi.yaml`.
- Tests: create under the matching test packages as named in each task.

---

### Task 1: Dependencies and configuration properties

**Files:**
- Modify: `apps/backend/pom.xml`
- Modify: `apps/backend/src/main/resources/application.yml`, `application-local.yml`
- Create: `SecurityProperties.java`, `LimitProperties.java`, `IngestionProperties.java` (in `com.sheltr.config` / `com.sheltr.ingestion.config`)
- Test: `apps/backend/src/test/java/com/sheltr/config/SecurityPropertiesTest.java`

**Interfaces:**
- Produces: `SecurityProperties(String username, String password, String passwordHash)` with `SecurityProperties.validated()`; `LimitProperties(int askPerMinute, int maxAsksPerDay)`; `IngestionProperties(List<String> allowedHosts)`.

- [ ] **Step 1: Write the failing test**

```java
class SecurityPropertiesTest {
    @Test
    void rejectsMissingPasswordWhenNotLocal() {
        var props = new SecurityProperties("admin", null, null);
        assertThatThrownBy(() -> props.requireCredentials(false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SHELTR_ADMIN_PASSWORD");
    }

    @Test
    void rejectsBothPasswordAndHash() {
        var props = new SecurityProperties("admin", "secret", "$2a$10$hash");
        assertThatThrownBy(() -> props.requireCredentials(false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not both");
    }

    @Test
    void allowsLocalDefaultCredentialOnlyWhenLocal() {
        var props = new SecurityProperties("admin", "{noop}local-dev", null);
        assertThatCode(() -> props.requireCredentials(true)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `$MVN test -Dtest=SecurityPropertiesTest` (use the Maven prefix above)
Expected: compilation failure, `SecurityProperties` missing.

- [ ] **Step 3: Implement the properties and dependencies**

Add to `pom.xml` dependencies: `org.springframework.boot:spring-boot-starter-security` and `org.springframework.boot:spring-boot-starter-validation` (versions managed by the Boot parent).

`SecurityProperties`: a record annotated `@ConfigurationProperties("sheltr.security.admin")` with a `requireCredentials(boolean local)` method that throws `IllegalStateException` when: both password and hash are set; neither is set and not local; the local profile is active and the password is absent. Messages must name the environment variables `SHELTR_ADMIN_PASSWORD` and `SHELTR_ADMIN_PASSWORD_HASH`.

`LimitProperties`: `@ConfigurationProperties("sheltr.limits")`, defaults 10 and 1000, reject values below 1 in a compact constructor.

`IngestionProperties`: `@ConfigurationProperties("sheltr.ingestion")`, `List<String> allowedHosts` defaulting to empty, copied immutably.

Register with `@ConfigurationPropertiesScan` on `SheltrBackendApplication` if not already present.

`application.yml`: add `sheltr.security.admin.username: admin`, `sheltr.security.admin.password: ${SHELTR_ADMIN_PASSWORD:}`, `sheltr.security.admin.password-hash: ${SHELTR_ADMIN_PASSWORD_HASH:}`, `sheltr.limits.ask-per-minute: 10`, `sheltr.limits.max-asks-per-day: 1000`, `sheltr.ingestion.allowed-hosts: [www.ready.gov]`. Comment each key.

`application-local.yml`: `sheltr.security.admin.password: "{noop}local-dev-only"` with a comment that this is a development credential and must never be used outside local development.

- [ ] **Step 4: Run and commit**

Run the focused test; expected PASS. Run `mvn -q test`; expected all green.

```bash
git add apps/backend/pom.xml apps/backend/src/main apps/backend/src/test
git commit -m "feat(config): add security, limit and ingestion properties with fail-fast checks"
```

---

### Task 2: Validation and single error advice (S1)

**Files:**
- Modify: `AskRequestDto.java`, `TriggerIngestionRequestDto.java`
- Modify: `ConversationController.java`, `IngestionController.java` (remove local `@ExceptionHandler` methods that the advice replaces)
- Create: `apps/backend/src/main/java/com/sheltr/interfaces/ApiError.java`, `ApiErrorAdvice.java`
- Test: `apps/backend/src/test/java/com/sheltr/interfaces/ApiErrorAdviceTest.java`, `AskRequestValidationTest.java`

**Interfaces:**
- Produces: `ApiError(String error, String message, Instant timestamp)`; `LimitExceededException extends RuntimeException` in `com.sheltr.interfaces` (used by Tasks 6 and 7); `ApiErrorAdvice` mapping `MethodArgumentNotValidException`→400, `ConversationNotFoundException`→404, `AnswerGenerationFailedException`→502, `StaleEmbeddingsException`→503, `LimitExceededException`→429, `Exception`→500 generic.

- [ ] **Step 1: Failing tests**

`AskRequestValidationTest` (plain Validator, no Spring context):

```java
class AskRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsBlankQuestion() {
        assertThat(validator.validate(new AskRequestDto("  ", List.of()))).isNotEmpty();
    }

    @Test
    void rejectsQuestionLongerThan2000Characters() {
        assertThat(validator.validate(new AskRequestDto("x".repeat(2001), List.of()))).isNotEmpty();
    }

    @Test
    void acceptsQuestionOf2000Characters() {
        assertThat(validator.validate(new AskRequestDto("x".repeat(2000), List.of()))).isEmpty();
    }
}
```

`ApiErrorAdviceTest`: call the advice methods directly and assert status and that `message` never contains the exception's message for the generic case (use an exception with text `"secret-internal-detail"` and assert the response body does not contain it).

- [ ] **Step 2: Run, confirm they fail**

Expected: compile or assertion failure (no constraints, no advice).

- [ ] **Step 3: Implement**

`AskRequestDto`: `@NotBlank @Size(max = 2000) String question`, `@Size(max = 12) List<String> activeHazards`. Keep the one-argument constructor.

`TriggerIngestionRequestDto`: `@NotBlank String sourceUri`; controllers take `@Valid @RequestBody`.

`ApiErrorAdvice`: `@RestControllerAdvice` in `com.sheltr.interfaces`. For generic `Exception`, log at ERROR with the exception and return the constant message `"An unexpected error occurred."`. For validation, the message is the first field error as `"<field>: <message>"`. Move the four existing `@ExceptionHandler` methods out of `ConversationController` into the advice with identical status codes (404, 502, 503 for the existing exceptions).

- [ ] **Step 4: Run and commit**

Focused tests PASS; full suite PASS (existing `ConversationControllerHazardTest` expects 400 for blank question and unknown hazard; keep that passing).

```bash
git commit -m "feat(api): validate requests and centralise error responses"
```

---

### Task 3: HTTP timeouts and redirect refusal (X3)

**Files:**
- Modify: `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/HttpPdfConnector.java`
- Modify: `application.yml` (comment on timeouts); verify Spring AI timeout property names (see step 3)
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/HttpPdfConnectorTimeoutTest.java`

- [ ] **Step 1: Failing test**

Use a local `com.sun.net.httpserver.HttpServer` on an ephemeral port:
- A handler returning `302` with `Location` to another path → `fetch` throws `RuntimeException` whose message contains `HTTP 302`.
- A handler that sleeps 70 s → `fetch` throws within 65 s (use a constructor that accepts durations so the test can use 1 s timeouts; default constructor uses 10 s / 60 s).

- [ ] **Step 2: Confirm failure** (no timeouts or redirect policy yet; the 302 test currently follows redirects or returns the body).

- [ ] **Step 3: Implement**

Constructor `HttpPdfConnector(Duration connectTimeout, Duration requestTimeout)` plus a no-arg constructor using 10 s and 60 s. Build the client with `HttpClient.newBuilder().connectTimeout(...).followRedirects(HttpClient.Redirect.NEVER).build()` and set `HttpRequest.newBuilder(...).timeout(requestTimeout)`. Treat any 3xx as failure with the status in the message.

Spring AI provider timeouts: inspect the Spring AI 2.0 jars in `~/.m2/repository/org/springframework/ai` for timeout properties on the Ollama and Google GenAI chat and embedding options (search the `*-configuration-metadata.json` files). Set every timeout property that exists in `application.yml` and `application-gemini.yml`. If a provider has none, add a comment in the YAML and list it in the report as a residual risk. Do not add an executor wrapper.

- [ ] **Step 4: Run and commit**

Focused test PASS, full suite PASS.

```bash
git commit -m "feat(ingestion): add HTTP timeouts and refuse redirects"
```

---

### Task 4: Admin authentication (X1)

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/config/SecurityConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/config/AdminSecurityIntegrationTest.java`
- Modify: `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionControllerIntegrationTest.java`, `LocalIngestionToRagEndToEndTest.java`, `IngestionToRagEndToEndTest.java` (send admin credentials via `withBasicAuth` where they call `/api/v1/admin/**`)

**Interfaces:**
- Consumes: `SecurityProperties` (Task 1).
- Produces: `SecurityFilterChain` bean; `UserDetailsService` with one user from properties; `PasswordEncoder` (`PasswordEncoderFactories.createDelegatingPasswordEncoder()` so `{noop}` and bcrypt both work).

- [ ] **Step 1: Failing integration test** (`@SpringBootTest` with `RANDOM_PORT`, `@ActiveProfiles("local")`, using `TestRestTemplate`)

- `POST /api/v1/admin/ingestion/runs` without auth → 401.
- With `admin` and `local-dev-only` → not 401 or 403 (body validation may give 400; assert `!= 401` and `!= 403`).
- `GET /api/v1/conversations` anonymous → not 401 (public route still open).

- [ ] **Step 2: Confirm failure** (currently 202 or 400 without auth).

- [ ] **Step 3: Implement**

`SecurityConfig`: `SecurityFilterChain` with `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`, `anyRequest().permitAll()`, `httpBasic(...)`, CSRF disabled for the stateless API (justified: HTTP Basic, no cookies, no browser form posts to these routes). Session creation stopped. Entry point returns 401 with no body text beyond the standard error body (reuse `ApiError` shape by writing JSON directly, or accept the default empty 401; choose the default and note it).

The user comes from `SecurityProperties`, with the role `ADMIN`. Call `requireCredentials(isLocalProfileActive)` at bean creation so startup fails fast outside local when no credential is set.

Management endpoints (`/actuator/health`, `/actuator/info`) remain `permitAll`.

- [ ] **Step 4: Update admin integration tests** to use `withBasicAuth("admin", "local-dev-only")`. Do not change their expected statuses.

- [ ] **Step 5: Run and commit**

Focused tests PASS. Full suite PASS.

```bash
git commit -m "feat(security): require HTTP Basic admin role on admin routes"
```

---

### Task 5: Ingestion fetch allowlist (X1, second part)

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourcePolicy.java`, `ingestion/config/HostAllowlistSourcePolicy.java`
- Modify: `ingestion/application/IngestDocumentService.java` (take `SourcePolicy`), `ingestion/config/IngestDocumentServiceConfig.java`, `ingestion/interfaces/IngestionController.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/config/HostAllowlistSourcePolicyTest.java`, `ingestion/application/IngestDocumentServiceAllowlistTest.java`

**Interfaces:**
- Produces: `SourcePolicy.allows(String sourceUri)`; `IngestDocumentService` constructor gains a trailing `SourcePolicy` parameter; `ingest(...)` returns a FAILED run with message `"Source host not allowed: <host>"` before calling `connector.fetch`.

- [ ] **Step 1: Failing tests**

`HostAllowlistSourcePolicyTest`: `https://www.ready.gov/x.pdf` allowed with `[www.ready.gov]`; `https://evil.example/x.pdf` denied; empty list denies all; `https://www.ready.gov.evil.example/x.pdf` denied (exact host match); `file:///etc/passwd` and a URI with no host denied.

`IngestDocumentServiceAllowlistTest`: a fake connector that counts `fetch` calls; a denying policy → run FAILED, fetch count 0.

- [ ] **Step 2: Confirm failure.**

- [ ] **Step 3: Implement.** Parse with `URI.create`, require scheme `https`, compare `getHost()` exactly (case-insensitive) against the list. Invalid URI syntax → denied. In `IngestDocumentService.ingest`, check the policy first, inside the existing try so the run is recorded as FAILED. The controller maps a denied host to 400 by checking the policy before calling `ingest`, returning the same message.

- [ ] **Step 4: Run and commit.** Full suite PASS.

```bash
git commit -m "feat(ingestion): restrict fetches to an allowlist of hosts"
```

---

### Task 6: Per-client ask rate limit (X2a)

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/interfaces/FixedWindowLimiter.java`, `AskRateLimitFilter.java`, `LimitExceededException.java`
- Create: `apps/backend/src/main/java/com/sheltr/config/RateLimitConfig.java` (registers the filter for `/api/v1/conversations/*/ask`)
- Modify: `ApiErrorAdvice` (map `LimitExceededException` → 429) if not already done
- Test: `FixedWindowLimiterTest.java`, `AskRateLimitFilterTest.java`

**Interfaces:**
- Produces: `FixedWindowLimiter(int limitPerWindow, Duration window, Clock clock, int maxKeys)` with `boolean tryAcquire(String key)`; eviction removes windows older than `window` when `maxKeys` is reached; the filter returns 429 JSON with `Retry-After` seconds.

- [ ] **Step 1: Failing tests**

- 10 acquisitions for one key succeed, the 11th fails, and a different key still succeeds.
- After advancing the fake clock past the window, the key is allowed again.
- With `maxKeys = 2`, a third key evicts stale entries and the map never exceeds 2.
- Filter: 11th POST to `/ask` from the same remote address returns 429 with `Retry-After` header; the downstream chain is not invoked for that request (use a `FilterChain` that counts calls). Key is `request.getRemoteAddr()` only; `X-Forwarded-For` is ignored.

- [ ] **Step 2: Confirm failure.**

- [ ] **Step 3: Implement.** Use `ConcurrentHashMap` and a per-key `Window(startMillis, count)`; synchronise per entry through `compute`. Keep it under 100 lines. Register the filter with `FilterRegistrationBean` restricted to POST requests on `/api/v1/conversations/*/ask`.

- [ ] **Step 4: Run and commit.** Full suite PASS.

```bash
git commit -m "feat(api): rate limit ask requests per client address"
```

---

### Task 7: Daily ask cap (X2b)

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/AskQuota.java`
- Modify: `ai/domain/ConversationRepository.java`, `ai/infrastructure/JooqConversationRepository.java`, `ai/application/AskQuestionService.java`, `ai/config/AskQuestionServiceConfig.java`
- Create: `LimitExceededException` reuse (from Task 6) for the cap
- Test: `AskQuotaTest.java`, `AskQuestionServiceQuotaTest.java`, `JooqConversationRepositoryCountIntegrationTest.java` (Testcontainers, same pattern as existing tests)

**Interfaces:**
- Produces: `AskQuota.allows(long askedToday, int maxPerDay)` (strict: `askedToday < maxPerDay`); `ConversationRepository.countUserMessagesSince(Instant since)`; `AskQuestionService` takes `AskQuota`-related `int maxAsksPerDay` and a `Clock`; throws `LimitExceededException` before the pipeline when the cap is reached.

- [ ] **Step 1: Failing tests**

- `AskQuotaTest`: `allows(999, 1000)` true; `allows(1000, 1000)` false; `allows(0, 0)` false.
- `AskQuestionServiceQuotaTest`: a fake repository reporting 5 asks today with cap 5 → `LimitExceededException`, and the retriever is never called (use a retriever lambda that throws AssertionError).
- Integration: insert two USER messages dated today and one dated yesterday; `countUserMessagesSince(startOfToday)` returns 2.

- [ ] **Step 2: Confirm failure.**

- [ ] **Step 3: Implement.** The cap check is the first step in `ask(...)` after loading the conversation. The count query: `SELECT count(*) FROM ai.messages WHERE role = 'USER' AND created_at >= ?`. Add an index migration `V8__ai_messages_role_created_index.sql`: `CREATE INDEX IF NOT EXISTS idx_ai_messages_role_created ON ai.messages (role, created_at);`. Start of today is computed from the injected `Clock` at UTC midnight.

- [ ] **Step 4: Run and commit.** Full suite PASS, including `MigrationIntegrationTest` (it lists tables, not indexes; still green).

```bash
git commit -m "feat(ai): enforce a daily cap on answered questions before generation"
```

---

### Task 8: Documentation and contract

**Files:**
- Create: `docs/adr/0014-admin-auth-and-request-limits.md`
- Modify: `README.md` (environment variables, local development password, limit settings), `docs/openapi/openapi.yaml` (400, 401, 403, 429 responses for admin ingestion and `/ask`)

- [ ] **Step 1:** Write ADR-0014 with: context (assessment X1–X3, S1), decisions (HTTP Basic admin role, fetch allowlist with no redirects, in-app limiter as backstop, database daily cap, timeouts), consequences, residual risks from the spec §6.
- [ ] **Step 2:** README: `SHELTR_ADMIN_PASSWORD` or `SHELTR_ADMIN_PASSWORD_HASH` required outside local; local development password `local-dev-only`; `sheltr.limits.*` and `sheltr.ingestion.allowed-hosts`.
- [ ] **Step 3:** OpenAPI: add the responses. Keep HURRICANE-related drift out of scope (Task 15 of the corpus plan).
- [ ] **Step 4: Commit**

```bash
git commit -m "docs: record Phase 0 security and limit decisions"
```

---

## Verification after Task 8

- `mvn -q test` from `apps/backend`: all tests pass, gated tests skipped unless their variables are set.
- Manual check with the app running: `curl -i -X POST localhost:8080/api/v1/admin/ingestion/runs` → 401; with `-u admin:local-dev-only` and a non-allowlisted host → 400.
- Report any test that could not run and say so.
