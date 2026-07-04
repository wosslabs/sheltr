# ADR-0007: Bounded context boundaries and Phase 1 scope

## Status
Accepted

## Context
The product spans 11 candidate bounded contexts: Knowledge, Search,
Disaster, User, Location, Emergency, Notification, Administration,
Analytics, AI, Ingestion. Designing and building all 11 up front, before
any of them has a working implementation to validate the model against,
risks over-designing contexts whose real requirements aren't known yet
(Notification, Analytics, Administration in particular).

## Decision
Full DDD design (aggregates, value objects, domain events, repository
ports) in Phase 1 covers only the 4 contexts Phase 2 actually implements:
**Knowledge**, **Search**, **Ingestion**, **AI**. The remaining 7 contexts
get a one-paragraph stub (name, responsibility, a guess at a future root
entity) in `docs/architecture/domain-model.md`, with full design deferred
to whichever phase implements them.

## Consequences
- Phase 1's domain model doc is smaller and more accurate — it describes
  code that's about to be built, not speculative future code.
- Contexts like User, Location, Notification will need their own design
  pass (own mini brainstorm) before their implementing phase starts.

## Alternatives Considered
- **Full design for all 11 now**: rejected — most of that design would be
  invalidated or reworked once real requirements (e.g. what the mobile app
  actually needs from Location) are known.
