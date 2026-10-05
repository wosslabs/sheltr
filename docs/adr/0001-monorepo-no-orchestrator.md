# ADR-0001: Monorepo with no build orchestrator

Status: Superseded by ADR-0015 where it describes the server (see that ADR).

## Status
Accepted

## Context
Sheltr's monorepo spans Maven (backend), Flutter/Dart (mobile), and likely
npm/Vite (admin-web) — three different native build tools. Tools like
Turborepo and Nx are JS-workspace-first; neither natively understands Maven
or Flutter build graphs without wrapper scripts that shell out anyway.

## Decision
Use a plain folder-convention monorepo (`apps/`, `packages/`, `docs/`,
`infra/`). A root `Makefile` provides shared entrypoints (`make build`,
`make test`, `make up`, `make down`) that delegate to each app's native
build tool.

## Consequences
- No caching/affected-detection across apps — acceptable at current scale
  (one backend, mobile/admin-web not yet built).
- Each app's CI job runs independently; no shared task graph to maintain.
- If cross-app build orchestration becomes a real pain point later (e.g.
  shared TypeScript types consumed by both admin-web and mobile web
  builds), revisit with Nx (better polyglot plugin support than Turborepo).

## Alternatives Considered
- **Turborepo**: rejected — JS-first, backend/mobile gain nothing from its
  caching model, adds a tool to learn for no benefit yet.
- **Nx**: rejected for now — same JS-first bias, steeper setup cost than
  justified until there are 2+ JS/TS apps sharing code.
