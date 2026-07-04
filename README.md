# Sheltr — Disaster Response Knowledge Assistant

Sheltr is an open-source RAG platform that answers disaster-response
questions (earthquake, flood, hurricane, tornado, tsunami, wildfire,
extreme heat, winter storm) with citation-grounded answers sourced from
government and NGO guidance (FEMA, American Red Cross, WHO, UNOCHA).
Sheltr never invents emergency guidance — every answer cites its source.

## Status

Phase 1 (foundation) complete: repo scaffold, architecture decision
records, C4 diagrams, domain model, database schema, and OpenAPI contract.
See `docs/superpowers/specs/2026-07-04-phase1-foundation-design.md` for
the full spec and `docs/superpowers/plans/2026-07-04-phase1-foundation.md`
for how it was built.

Backend RAG implementation, mobile app, and deployment are future phases.

## Repo layout

- `apps/backend` — Spring Boot backend (Java 25, hexagonal architecture)
- `apps/mobile` — Flutter mobile app (placeholder, Phase 3)
- `apps/admin-web` — admin curation UI (placeholder)
- `packages/` — shared code across apps (placeholder until a second app exists)
- `docs/adr` — architecture decision records
- `docs/architecture` — C4 diagrams and domain model
- `docs/openapi` — API contract
- `infra/podman` — local development environment (Postgres + pgvector)
- `infra/k8s` — Kubernetes manifests (placeholder)

## Local development

Requires [Podman Desktop](https://podman-desktop.io/) (or the Podman CLI with a running
machine) — this project targets Podman only, not Docker Desktop.

```bash
make up       # start Postgres + pgvector (podman compose)
make migrate  # apply Flyway migrations directly, without booting the app
make build    # build the backend
make test     # run backend tests (includes Testcontainers migration test — needs Podman)
make down     # stop Postgres
```

## License

Apache License 2.0 — see `LICENSE`.
