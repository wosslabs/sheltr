# ADR-0005: jOOQ over JPA/Hibernate for persistence

## Status
Accepted

## Context
Hybrid search and pgvector similarity queries (`ORDER BY embedding <=>
:queryVector`) need precise, typed SQL that JPA/Hibernate's generated
queries and entity-lifecycle model actively fight against. Hexagonal
adapters (ADR-0002) should be thin translators between domain objects and
SQL, not carriers of ORM entity state.

## Decision
Use jOOQ for all persistence adapters. Schema is the source of truth
(Flyway migrations, ADR-0006); jOOQ generates typed query DSL code from
the migrated schema at build time.

## Consequences
- Full control over generated SQL, including pgvector operators and
  `tsvector`/`GIN` hybrid-search queries.
- No entity lifecycle (dirty checking, lazy loading, N+1 surprises) to
  reason about — adapters map explicit rows to domain objects and back.
- Requires a live database (or Testcontainers) to run codegen, unlike
  annotation-driven JPA entities. Mitigated by gating codegen behind a
  Maven profile (`jooq-codegen`) that isn't part of the default build.

## Alternatives Considered
- **JPA/Hibernate**: rejected — ORM entity model doesn't fit
  vector-similarity and hybrid-search query patterns well, and encourages
  anemic-adapter/rich-entity blurring that fights the hexagonal boundary.
- **Plain JDBC**: rejected — no type safety on generated SQL, more
  boilerplate than jOOQ's generated DSL.
