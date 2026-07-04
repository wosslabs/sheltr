# ADR-0009: Apache License 2.0

## Status
Accepted

## Context
Sheltr is open source. Needed a license that: grants an explicit patent
license (relevant given multiple potential contributors and enterprise
adopters), is widely understood/accepted by enterprises wanting to deploy
or contribute, and is consistent with the license of major dependencies in
this stack (Spring, much of the Google ecosystem).

## Decision
License the project under Apache License 2.0.

## Consequences
- Permissive — downstream users may create proprietary derivatives; no
  copyleft obligation to publish modifications.
- Explicit patent grant reduces patent-litigation risk for adopters,
  compared to MIT.

## Alternatives Considered
- **MIT**: rejected — simpler but no patent grant clause, weaker
  protection for an enterprise/humanitarian-adoption context.
- **AGPL-3.0**: rejected — copyleft could deter enterprise or government
  adopters from integrating Sheltr into their own systems, which works
  against the goal of wide humanitarian-guidance distribution.
