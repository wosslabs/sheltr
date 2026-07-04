# ADR-0006: Flyway for schema migration and versioning

## Status
Accepted

## Context
Need versioned, repeatable schema changes across `knowledge`, `ingestion`,
and `ai` Postgres schemas, applied consistently in dev, CI, and
production.

## Decision
Use Flyway with a single migration location
(`apps/backend/src/main/resources/db/migration`) and a single, globally
ordered version sequence (`V1`, `V2`, `V3`, ...) even though migrations
target different Postgres schemas. Each migration issues its own `CREATE
SCHEMA IF NOT EXISTS` and schema-qualified DDL.

## Consequences
- One `flyway_schema_history` table tracks the whole database's evolution
  — simpler operationally than per-schema history tables, at the cost of
  interleaving unrelated contexts' migrations in one numeric sequence.
- Flyway runs automatically on Spring Boot startup (`spring.flyway.enabled:
  true`), so `make up` + starting the backend always leaves the DB at the
  latest schema.

## Alternatives Considered
- **Per-schema Flyway configurations with separate history tables**:
  rejected for Phase 1 — more moving parts (multiple `FluentConfiguration`
  beans) for a benefit (independent context migration ordering) that
  doesn't matter until contexts are deployed as separate services.
