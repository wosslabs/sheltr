# ADR-0010: Podman (not Docker) as the supported container runtime

Status: Superseded by ADR-0015 where it describes the server (see that ADR).

## Status
Accepted

## Context
Local development needs a container runtime for Postgres+pgvector (`infra/podman/compose.yaml`)
and for Testcontainers-backed integration tests (`MigrationIntegrationTest`). The primary
development machine for this project has no `docker` CLI installed at all — only Podman, with
a `podman-machine-default` VM. Podman's `podman compose` subcommand transparently wraps an
external Compose provider (e.g. the standalone `docker-compose` binary) against the local
Podman socket, and Podman machines expose a Docker-API-compatible Unix socket
(`/var/run/docker.sock`) that Testcontainers' Java client auto-detects with no extra
configuration.

## Decision
Standardize on Podman (via Podman Desktop or the Podman CLI) as the project's only supported
local container runtime. `infra/podman/compose.yaml` is invoked with `podman compose`, wired
through the root `Makefile`'s `up`/`down`/`migrate` targets. No committed file assumes a
`docker` CLI is present. Documentation (README, this spec) tells contributors to install
Podman Desktop, not Docker Desktop.

## Consequences
- Contributors need Podman Desktop (or the Podman CLI + a running machine) instead of Docker
  Desktop — a real environment requirement to document clearly in onboarding docs.
- `podman compose` requires an external Compose provider (`docker-compose` or
  `podman-compose`) to be installed alongside Podman; this is a one-time setup step, not a
  per-invocation dependency.
- Testcontainers works with zero extra environment variables on a properly configured Podman
  machine (Docker-API-compatible socket auto-detected) — no `DOCKER_HOST` export or Ryuk
  workaround needed once the machine is set up correctly.
- CI (when built in a later phase) must provision Podman rather than assuming Docker is
  preinstalled on the runner image — a concrete follow-up for the CI/CD phase.

## Alternatives Considered
- **Docker Desktop**: rejected — not what contributors on this project's reference
  development machine have installed; standardizing on it would mean documenting a tool the
  primary maintainer doesn't use, and asking contributors to install a second container
  runtime alongside Podman for no functional benefit (Podman is Docker-API-compatible for
  every use this project makes of a container runtime: Compose and Testcontainers).
- **Supporting both Docker and Podman with conditional tooling**: rejected for Phase 1 —
  adds branching logic to the Makefile and docs for a benefit (supporting contributors who
  specifically prefer Docker Desktop) not currently needed; revisit if a real contributor
  requests it.
