# ADR-0003: Google Gemini only, accessed exclusively through Spring AI

## Status
Accepted

## Context
Product requirement: Gemini is the only LLM provider (no OpenAI, no
Anthropic, no local LLM). At the same time, domain/application code must
not hard-couple to a vendor SDK, per ADR-0002.

## Decision
All Gemini access (embeddings, generation, query rewriting, HyDE,
summarization) goes through Spring AI's portable abstractions
(`ChatModel`, `EmbeddingModel`, etc.). No `com.google.*` Gemini SDK type
may appear outside an `infrastructure` adapter package.

## Consequences
- If Spring AI adds/changes its Gemini starter, only adapter code changes.
- Business logic (e.g. the AI context's `AnswerGenerator` port from the
  domain model) is provider-agnostic in principle, even though the product
  only ever configures Gemini.
- Not exercised in Phase 1 (no AI logic written yet) — this ADR governs
  Phase 2 implementation.

## Alternatives Considered
- **Direct Google GenAI SDK usage**: rejected — couples domain/application
  code to a vendor SDK, violates ADR-0002, makes unit testing AI-dependent
  logic require mocking a concrete SDK class instead of a domain port.
