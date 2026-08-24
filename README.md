# Sheltr — Disaster Response Knowledge Assistant

Sheltr is an open-source RAG platform that answers disaster-response
questions (earthquake, flood, hurricane, tornado, tsunami, wildfire,
extreme heat, winter storm) with citation-grounded answers sourced from
government and NGO guidance (FEMA, American Red Cross, WHO, UNOCHA).
Sheltr never invents emergency guidance — every answer cites its source.

## Status

Phase 1 (foundation), Phase 2 (RAG MVP), and Phase 3 (ingestion MVP) are
complete:

- **Phase 1** — repo scaffold, architecture decision records, C4 diagrams,
  domain model, database schema, and OpenAPI contract. See
  `docs/superpowers/specs/2026-07-04-phase1-foundation-design.md` and
  `docs/superpowers/plans/2026-07-04-phase1-foundation.md`.
- **Phase 2** — pgvector semantic search, citation-grounded answer
  generation (ADR-0008), and conversation/document REST APIs. See
  `docs/superpowers/specs/2026-07-04-phase2-rag-mvp-design.md` and
  `docs/superpowers/plans/2026-07-05-phase2-rag-mvp.md`.
- **Phase 3** — document ingestion pipeline (FEMA PDF connector, text
  cleaning/chunking, embedding backfill) wired end-to-end into the RAG
  loop via admin REST endpoints. See
  `docs/superpowers/specs/2026-07-13-ingestion-mvp-design.md` and
  `docs/superpowers/plans/2026-07-13-ingestion-mvp.md`.

Local/CPU model migration complete: the default Spring profile now runs fully
offline after initial setup, on a local Ollama-backed provider
(`qwen2.5:3b-instruct` for generation, `nomic-embed-text` for embedding) —
no API key or cloud dependency required to try Sheltr. Gemini remains
available as an opt-in profile for self-hosters who want cloud-hosted
answer quality. See `docs/adr/0011-local-ollama-default-provider.md` and
`docs/superpowers/specs/2026-08-03-local-model-migration-design.md` for
the full spec and `docs/superpowers/plans/2026-08-04-local-model-migration.md`
for how it was built.

Mobile app, admin curation UI, and deployment are future phases.

## Repo layout

- `apps/backend` — Spring Boot backend (Java 25, hexagonal architecture)
- `apps/mobile` — Flutter mobile app (placeholder, Phase 3)
- `apps/admin-web` — admin curation UI (placeholder)
- `packages/` — shared code across apps (placeholder until a second app exists)
- `docs/adr` — architecture decision records
- `docs/architecture` — C4 diagrams and domain model
- `docs/openapi` — API contract
- `infra/podman` — local development environment (Postgres + pgvector + Ollama)
- `infra/k8s` — Kubernetes manifests (placeholder)

## Local development

Requires [Podman Desktop](https://podman-desktop.io/) (or the Podman CLI with a running
machine) — this project targets Podman only, not Docker Desktop.

```bash
make up       # start Postgres + pgvector + Ollama (podman compose), then pull
              # qwen2.5:3b-instruct + nomic-embed-text (~2.5GB total, one-time
              # on first run — requires network access; cached locally after that)
make migrate  # apply Flyway migrations directly, without booting the app
make build    # build the backend
make test     # run backend tests (includes Testcontainers migration test — needs Podman)
make down     # stop Postgres + Ollama
```

After `make up` and `mvn -pl apps/backend spring-boot:run` (from repo root: `cd apps/backend && env -u SPRING_CONFIG_IMPORT mvn spring-boot:run`), open the chat UI at [http://127.0.0.1:8090/](http://127.0.0.1:8090/). The REST API stays on `:8080`. Opening `:8080` in a browser shows disconnected Lumina HTML because Tomcat is not the Lumina WebSocket server.

### Providers: `local` vs `gemini`

Sheltr supports two interchangeable AI providers, selected via the
`SPRING_PROFILES_ACTIVE` Spring profile:

- **`local` (default)** — uses the Ollama sidecar started by `make up`
  (`qwen2.5:3b-instruct` for generation, `nomic-embed-text` for embedding).
  No API key needed. Runs on CPU; no GPU required. Plan for roughly 8-16GB
  of available RAM. After the one-time model pull, answer generation makes
  no outbound network calls.
- **`gemini` (opt-in)** — uses Google's Gemini models for better answer
  quality and lower latency at the cost of a cloud dependency. Requires a
  `GEMINI_API_KEY` environment variable. Enable with
  `SPRING_PROFILES_ACTIVE=gemini`.

If you only plan to use `gemini`, skip the local model pull (which needs
network access and ~2.5GB of download) with:

```bash
SHELTR_PROVIDER=gemini make up
```

See `docs/adr/0011-local-ollama-default-provider.md` for the full rationale.

#### Switching between `local` and `gemini`

Embeddings from the two providers are **not interchangeable** — both happen
to produce 768-dimensional vectors, so switching `SPRING_PROFILES_ACTIVE`
against a database that already has embeddings from the other provider will
not raise any error. Retrieval will simply, silently degrade, since the mixed
embedding space no longer clusters meaningfully (everything falls below the
relevance threshold). `make seed-embeddings`/backfill only fills rows where
`embedding IS NULL`, so it will not fix already-populated stale vectors on
its own.

If you switch profiles against an existing database, recompute every chunk's
embedding under the newly active profile:

```bash
psql "postgresql://sheltr:sheltr@localhost:5432/sheltr" -c "UPDATE knowledge.chunks SET embedding = NULL;"
make seed-embeddings
```

## License

Apache License 2.0 — see `LICENSE`.
