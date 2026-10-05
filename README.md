# Sheltr — Disaster Response Knowledge Assistant

Sheltr is an open-source RAG platform that answers disaster-response
questions (earthquake, flood, tropical cyclone, tornado, tsunami, wildfire,
extreme heat, winter storm, landslide, glacial lake flood, volcano, drought)
with citation-grounded answers. Sheltr answers only from official documents listed in `corpus/manifest.yaml`, and every answer cites the passages it used.

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

The rows seeded by migration V5 are test fixtures, not official text. They have status `SEED` and are never returned to users.

`corpus/manifest.yaml` is the approved corpus. It currently lists no documents: no entry has been approved yet. See `docs/adr/0013-corpus-manifest.md`. Retrieval, reranking, and the relevance judge are staged behind ports; the default for each is not yet chosen by the eval. See `docs/adr/0012-hybrid-retrieval-and-relevance-judge.md`.

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
make ingest-corpus  # load the approved corpus from corpus/manifest.yaml
make build    # build the backend
make test     # run backend tests (includes Testcontainers migration test — needs Podman)
make eval CONFIG=name   # run the eval question set; see docs/superpowers/specs/2026-10-04-corpus-eval-retrieval-design.md
make down     # stop Postgres + Ollama
```

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

The backend will not start without an admin password in every profile.
Export one of these before running it (`SHELTR_ADMIN_PASSWORD` for a plain
value, or `SHELTR_ADMIN_PASSWORD_HASH` for a bcrypt hash, never both):

```bash
export SHELTR_ADMIN_PASSWORD='choose-a-local-password'
```

There is no default admin password. Admin routes (`/api/v1/admin/**`) use HTTP
Basic with user `admin`.

#### Limits, ingestion and timeouts

Defaults are in `apps/backend/src/main/resources/application.yml`; each can be
overridden with the usual Spring environment variables (for example
`SHELTR_LIMITS_ASK_PER_MINUTE`).

| Setting | Default | Meaning |
| --- | --- | --- |
| `sheltr.limits.ask-per-minute` | `10` | Requests per minute per client address on `/ask`; excess gets `429` with `Retry-After`. Per instance: behind a proxy, the proxy limit is primary. |
| `sheltr.limits.max-asks-per-day` | `1000` | Questions per UTC day, counted from the database; excess gets `429` before any LLM call. |
| `sheltr.ingestion.allowed-hosts` | `www.ready.gov` | Hosts the ingestion connector may fetch, over https on the default port. Empty denies all. Redirects are never followed. |
| `sheltr.ingestion.connect-timeout` | `10s` | Source fetch connect timeout. |
| `sheltr.ingestion.request-timeout` | `60s` | Source fetch wait for response headers. |
| `spring.http.clients.connect-timeout` | `5s` | Connect timeout for Boot-built HTTP clients, including Ollama. |
| `spring.http.clients.read-timeout` | `120s` | Read timeout for Boot-built HTTP clients, including Ollama. The Gemini SDK does not use it. |

See `docs/adr/0014-admin-auth-and-request-limits.md` for the decisions and
residual risks.

If you only plan to use `gemini`, skip the local model pull (which needs
network access and ~2.5GB of download) with:

```bash
SHELTR_PROVIDER=gemini make up
```

See `docs/adr/0011-local-ollama-default-provider.md` for the full rationale.

#### Switching between `local` and `gemini`

Each chunk records the embedding model that produced its vector. If you switch to a profile with a different embedding model, Sheltr refuses to answer questions (HTTP 503) until you run `make seed-embeddings` under the new profile, which re-embeds every chunk.

## License

Apache License 2.0 — see `LICENSE`.
