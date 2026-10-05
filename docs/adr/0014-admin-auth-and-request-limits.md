# ADR-0014: Admin authentication and request limits

## Status
Accepted (Phase 0 hardening, 2026-10-04)

Design: `docs/superpowers/specs/2026-10-04-phase0-hardening-design.md`
Plan: `docs/superpowers/plans/2026-10-04-phase0-hardening.md`

## Context
The architectural assessment found four issues that block any public exposure of the backend:

- **X1** — the admin ingestion endpoints (`/api/v1/admin/ingestion/runs`) were unauthenticated,
  and the fetch URL was caller-supplied, so any client could make the server issue outbound
  requests to arbitrary hosts.
- **X2** — `POST /api/v1/conversations/{id}/ask` was anonymous and unlimited, and each call
  pays for an LLM generation.
- **X3** — external HTTP calls (the PDF connector and the Boot-built clients) had no timeouts.
- **S1** — no bean validation, no global error handler, and unbounded question length.

Phase 0 changes no answer logic, retrieval, corpus or eval behaviour.

## Decision

**Admin authentication.** `spring-boot-starter-security` protects `/api/v1/admin/**` with HTTP
Basic and role `ADMIN`. All other `/api/**` routes stay public (the citizen API). Actuator is
locked down beyond `health` and `info`. Unauthenticated calls get `401`; authenticated non-admin
calls get `403`. CSRF is disabled because the admin API is stateless and uses Basic credentials,
not cookies.

**Credentials from the environment, in every profile.** `SHELTR_ADMIN_PASSWORD` (plaintext) or
`SHELTR_ADMIN_PASSWORD_HASH` (bcrypt), never both. Neither set fails startup and names both
variables. No admin password is written to YAML. A plaintext password is bcrypt-encoded at
startup and the plaintext is discarded; a hash is used as given, and its cost factor must be
04–31, checked at startup. Plaintext is never logged, and the credential holder does not
expose it through `toString`. Local developers export a value; there is no default.

**Ingestion fetch allowlist.** `sheltr.ingestion.allowed-hosts` lists the hostnames the
connector may fetch; empty denies all. A disallowed host returns `400` before any network call,
and only `https` on the default port is accepted. Redirects are never followed: the connector
uses `HttpClient.Redirect.NEVER` and fails on any 3xx, because approved sources publish their
final URL directly and following a redirect would let an allowed host send the fetch elsewhere.

**In-app ask limiter (backstop, not primary).** A servlet filter applies a per-client fixed
window of `sheltr.limits.ask-per-minute` (default 10) to `POST /api/v1/conversations/{id}/ask`
and returns `429` with `Retry-After`. The window map is bounded (hard cap of 10 000 tracked
clients), and the filter fails closed at the cap rather than evicting a live window. Expired
windows are swept at most once per window length. The client address is the socket address
only; `X-Forwarded-For` is not trusted. We chose not to add a third-party rate-limit library:
there is one endpoint in one process today, and the filter is small enough to test directly.

**Database daily cap.** `sheltr.limits.max-asks-per-day` (default 1000) counts `ai.messages`
rows with role `USER` created since midnight UTC, through one indexed query. Exceeding it
returns `429` before the LLM call, so a refused request costs nothing. A database count, not an
in-memory counter, means the cap survives restarts and holds across instances.

**Timeouts.** Source fetches: `sheltr.ingestion.connect-timeout` (10 s) and
`sheltr.ingestion.request-timeout` (60 s). Boot's shared RestClient timeouts,
`spring.http.clients.connect-timeout` (5 s) and `read-timeout` (120 s), bound every Boot-built
RestClient, which includes the Ollama client. All timeouts are configured in `application.yml`
with comments, not hard-coded.

**Validation and errors (S1).** Bean validation on request DTOs: question `@NotBlank` and at most
2000 characters; `activeHazards` at most 12 entries; `sourceUri` `@NotBlank`. One
`@RestControllerAdvice` in `com.sheltr.interfaces` returns every error as
`{ error, message, timestamp }`. Messages are client-safe; stack traces and internal exception
messages are logged, never returned.

## Consequences
- Admin endpoints are no longer open to anonymous callers, and the outbound fetch surface is
  limited to configured hosts over https without redirects.
- Every error response has one shape, so clients can parse failures uniformly.
- Operators must set an admin password before the backend starts, in every profile, including
  local. This is one extra step for developers.
- Admin access is a single shared account. There are no per-person identities or audit trail
  of who performed an action.
- The in-app limiter's budget is per instance. Multiple replicas multiply the effective limit,
  so a deployment that scales out needs an edge limit (see residuals).
- The daily cap is a soft cap under concurrency, and it counts failed generations.
- Gemini has no application-level request timeout (see residuals).

## Alternatives Considered
- **Hand-rolled admin checks**: rejected. A filter chain is harder to get wrong and is the
  standard way to protect a path namespace.
- **Third-party rate-limit library (e.g. Bucket4j)**: rejected for now. One endpoint and one
  process do not justify the dependency. Revisit when the limit must be shared across instances.
- **Trusting `X-Forwarded-For`**: rejected. Without a configured trusted-proxy list, a client can
  forge the header and evade the limit.
- **Following redirects on source fetches**: rejected. It would let an allowed host redirect the
  fetch to an unlisted host.
- **In-memory daily counter**: rejected. It resets on restart and does not hold across instances.

## Residual risks
1. The Spring `Environment` property source still holds the admin plaintext. It is not a bean,
   and `AdminCredentials.load` documents this in its Javadoc.
2. An authenticated admin calling a denied actuator path gets `403` with "Admin role required."
   This is cosmetic.
3. No edge proxy exists in the repository. The in-app limiter is a backstop, is per instance, and
   trusts the socket address only, so N replicas give N budgets. Behind a proxy, the proxy limit
   is primary.
4. The in-app limiter evicts nothing live. At 10 000 tracked clients it refuses new clients until
   windows expire (fail closed).
5. The daily cap is a soft cap under concurrency (check-then-act), and it counts failed
   generations.
6. Gemini (Spring AI google-genai) has no application-level request timeout. Ollama is bounded by
   `spring.http.clients.read-timeout` (120 s), which applies to every Boot RestClient.
7. The `HttpRequest` timeout bounds the wait for response headers, not a slow body stream.
8. The Lumina chat on port 8090 has no authorisation layer of its own and is not covered by the
   per-minute ask limit (the daily cap does apply). A future Lumina admin page must add its own
   authorisation.
9. The local development admin password must be exported (`SHELTR_ADMIN_PASSWORD`) before the
   app runs. There is no default.
10. The database default password in `application.yml` is the local Postgres container
    credential (spec §2.1 carve-out). It is not the admin credential.
11. Gated tests (`GEMINI_API_KEY`, `OLLAMA_AVAILABLE`, Podman) are skipped in CI-less runs.
    Phase 0 endpoints were verified by the non-gated suite only for the Gemini-gated admin calls.
