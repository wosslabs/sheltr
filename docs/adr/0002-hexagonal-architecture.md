# ADR-0002: Hexagonal architecture for the backend

Status: Superseded by ADR-0015 where it describes the server (see that ADR).

## Status
Accepted

## Context
The backend must support swapping infrastructure (e.g. a different vector
store, a different LLM provider) without rewriting business logic, and
must keep domain logic testable without spinning up Spring or a database.

## Decision
Structure each bounded context as:

```
REST API (interfaces) → Application Services → Domain ← Ports ← Adapters (infrastructure)
```

Domain packages contain zero framework imports (no `org.springframework.*`,
no `org.jooq.*`, no Gemini SDK types). Ports are plain Java interfaces
defined in the domain/application layer; adapters implementing those ports
live in `infrastructure` and depend inward on domain — never the reverse.

## Consequences
- Domain unit tests run with plain JUnit, no Spring context, fast.
- Adding a new persistence technology or LLM provider means writing a new
  adapter, not touching domain/application code.
- More interfaces/indirection than a simple CRUD app needs — justified
  here because the AI provider and vector store are both plausible future
  swap targets (ADR-0003, ADR-0004).

## Alternatives Considered
- **Transaction Script / anemic layered architecture**: rejected — couples
  business rules to Spring/JPA annotations, harder to unit test, harder to
  swap Gemini for another provider later.
