# ADR-0003: Google Gemini and Ollama, both accessed exclusively through Spring AI

## Status
Amended by ADR-0011 — see that ADR for the current default-provider decision. The core rule
below (no vendor SDK type outside `infrastructure`) is unchanged and now applies to both
providers.

## Context
Product requirement (original): Gemini is the only LLM provider (no OpenAI, no Anthropic, no
local LLM). At the same time, domain/application code must not hard-couple to a vendor SDK,
per ADR-0002.

This "Gemini only" requirement is superseded (see ADR-0011): as Sheltr moved toward an
open-source release, requiring every self-hoster to hold a Google API key and have internet
access just to run a disaster-response tool was recognized as an adoption barrier and a
single point of failure. The requirement now is that Gemini remains supported, but is no
longer the only option.

## Decision
All LLM/embedding access (embeddings, generation, and any future query rewriting, HyDE, or
summarization) goes through Spring AI's portable abstractions (`ChatModel`, `EmbeddingModel`,
etc.). No `com.google.*` Gemini SDK type or Ollama-specific type may appear outside an
`infrastructure` adapter package. This constraint made the ADR-0011 migration to a second
provider cheap: the existing `SpringAiEmbeddingGeneratorAdapter`/`SpringAiAnswerGeneratorAdapter`
classes needed no changes at all, only a Spring profile deciding which provider's
autoconfiguration supplies the beans they depend on.

## Consequences
- If Spring AI adds/changes a provider's starter, only configuration changes — adapter code
  is untouched, proven in practice by the ADR-0011 migration.
- Business logic (the AI context's `AnswerGenerator` port) is genuinely provider-agnostic now,
  not just in principle — it's exercised against two real providers.

## Alternatives Considered
- **Direct Google GenAI SDK usage**: rejected — couples domain/application code to a vendor
  SDK, violates ADR-0002, makes unit testing AI-dependent logic require mocking a concrete SDK
  class instead of a domain port.
