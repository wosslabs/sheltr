# Spring Boot 4.1 / Spring AI 2.0 Upgrade — Design

## Status

Proposed.

## Context

Sheltr currently runs Spring Boot 3.5.0 and Spring AI 1.1.2 (`apps/backend/pom.xml`).
We want to embed [Lumina](https://github.com/wosslabs/lumina) — a server-driven Java UI
framework with built-in RAG chat widgets (`ui.chatInput`, `ui.ai(TokenStream)`,
`ui.ragSources`, `ui.citation`) — directly inside the Sheltr backend to ship a first
chat UI, as the path to an MVP1 release (see
`docs/superpowers/specs/2026-08-07-lumina-chat-ui-design.md`).

Lumina's Spring-integration modules (`lumina-spring-boot-starter`, `lumina-spring-ai`)
pin, in Lumina's own parent POM: Spring Boot **4.1.0**, Spring AI **2.0.0**, Jetty
12.1.11, Java 25. Sheltr is already on Java 25. Both pinned versions are confirmed GA
(not milestones) and already resolvable from Maven Central (verified present in the
local `.m2` cache). Sheltr must be on these same major versions before the Lumina work
can begin — this is that upgrade, on its own, with no other behavior change.

## Goals

- Sheltr backend compiles and its full test suite passes on Spring Boot 4.1.0 and
  Spring AI 2.0.0, on **both** the `local` (Ollama) and `gemini` profiles.
- No functional change. This is purely a dependency-version bump; any code change is
  the minimum needed to keep existing behavior identical (e.g. an autoconfiguration
  class that was renamed between Spring AI versions).

## Non-Goals

- No new features. The Lumina integration is a separate, subsequent spec/plan that
  depends on this one being merged first.
- No refactoring beyond what the upgrade forces.

## Known risk areas

These are the specific places this upgrade is expected to touch, based on how Sheltr
currently depends on Spring Boot/Spring AI — not an exhaustive list of every possible
break, since the only reliable way to find those is to do the bump and run the suite:

1. **Spring AI autoconfiguration class names.** `application-local.yml` and
   `application-gemini.yml` both hard-code `spring.autoconfigure.exclude` entries by
   fully-qualified class name (e.g.
   `org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration`).
   A major Spring AI version bump is exactly the kind of change that renames or moves
   these classes. If an exclude silently stops matching, the wrong provider's
   autoconfiguration can activate alongside the intended one. Every exclude list must
   be re-verified against the Spring AI 2.0.0 jars (`unzip -l` the autoconfigure jars,
   same technique used during the local-model-migration to confirm
   `OllamaApiAutoConfiguration`'s existence).
2. **`ChatModel`/`ChatClient` API surface.** `SpringAiAnswerGeneratorAdapter` calls
   `chatModel.call(prompt)` directly (`org.springframework.ai.chat.model.ChatModel`).
   Confirm this method signature is unchanged in Spring AI 2.0.0, or update the one
   call site if it moved.
3. **jOOQ (3.19.11) / Testcontainers (1.20.4) / Flyway.** These are pinned
   independently of Spring Boot's dependency-management BOM (Sheltr uses `jOOQ` and
   `Flyway` directly, not `spring-boot-starter-jooq` — see ADR-0005). They likely ride
   through unaffected, but Spring Boot 4.1's own BOM could still pull in an
   incompatible transitive dependency (e.g. a different Postgres JDBC driver minor
   version than the one Sheltr pins at `42.7.3`). Needs a real build to confirm, not
   just a reading of release notes.
4. **Testcontainers-based integration tests.** Several existing tests
   (`MigrationIntegrationTest`, `RelevanceThresholdEvalTest`,
   `RagLoopEndToEndTest`/`LocalRagLoopEndToEndTest`, etc.) boot a full Spring context
   against a Testcontainers Postgres. These are the tests most likely to surface a
   subtle Boot 4.1 context-startup regression, since they exercise the most
   autoconfiguration surface area.

## Approach

Bump `spring-boot-starter-parent` to `4.1.0` and `spring-ai.version` to `2.0.0` in
`apps/backend/pom.xml`, then work top-down through compile errors, then test failures,
fixing only what's needed to restore identical behavior. Order of operations:

1. Bump versions, `mvn -DskipTests compile` — fix compile errors first (likely: any
   Spring AI class that moved packages).
2. `mvn test` on the default (`local`) profile — fix runtime/context-loading failures.
3. `mvn test` with `GEMINI_API_KEY` set — confirm the `gemini` profile also still
   loads and passes (this profile is exercised less often locally, per
   `docs/adr/0011-local-ollama-default-provider.md`, so it needs an explicit check,
   not just reliance on the default profile's green run).
4. Re-verify both `application-local.yml` and `application-gemini.yml`'s
   `spring.autoconfigure.exclude` lists against the new Spring AI 2.0.0 jars
   specifically (don't just trust that tests passing means the excludes are correct —
   a redundant-but-harmless autoconfiguration could pass tests while still being
   wrong).

## Gate / Definition of done

- `mvn test` green on `local` profile.
- `mvn test` green on `gemini` profile (`GEMINI_API_KEY` set).
- No behavior change: no new config keys beyond what's mechanically required by the
  version bump, no new dependencies beyond Spring Boot/Spring AI's own transitive
  updates.
- Autoconfiguration exclude lists in both profile YAMLs re-verified against the actual
  Spring AI 2.0.0 jars, not assumed correct by inheritance from 1.1.2.

## Alternatives considered

- **Skip the upgrade, integrate Lumina via its standalone path (`lumina-web`) instead
  of the Spring modules.** Rejected per explicit product direction: the goal is a
  native, in-process Spring AI integration (`lumina-spring-ai`'s `SpringAiChatClient`
  bridge), not a decoupled standalone UI process. The standalone path was the
  originally-explored option before this direction was chosen; it remains a fallback
  if the upgrade proves unexpectedly costly, but is not the current design.
