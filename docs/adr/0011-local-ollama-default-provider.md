# ADR-0011: Local (Ollama) is the default provider; Gemini is opt-in

## Status
Accepted

## Context
Sheltr is moving toward an open-source release. ADR-0003 originally mandated Gemini as the
only provider — every self-hoster needed a Google API key and internet access just to run
the app. For an open-source disaster-response tool, that's both an adoption barrier (a
stranger cloning the repo can't try it without signing up for a cloud API first) and a
reliability concern (a tool meant to work during a disaster shouldn't have a hard dependency
on a remote API being reachable).

## Decision
Add a local, CPU-capable provider path via Ollama (`qwen2.5:3b-instruct` for generation,
`nomic-embed-text` for embedding — both Apache-2.0 licensed, both pulled from Ollama's
library, which mirrors the same weights published on Hugging Face). Make it the default
Spring profile (`local`). Gemini remains fully supported behind an explicit
`SPRING_PROFILES_ACTIVE=gemini` opt-in for users who want cloud-hosted quality/speed instead.

The relevance threshold gating citation-grounded generation (ADR-0008) is profile-scoped and
independently, empirically calibrated per provider — `gemini`'s embedding space and `local`'s
embedding space produce different similarity-score distributions, so a single shared
threshold would be wrong for at least one of them.

## Consequences
- A stranger cloning the repo gets a fully working, offline, zero-signup experience with
  `make up` — no API key, no signup, no outbound network call at answer-generation time.
- Self-hosters who want better answer quality or lower latency than a 3B CPU model provides
  can still opt into Gemini with one environment variable.
- Two providers means two paths to keep passing in CI going forward (tracked as an open-source
  release requirement, not part of this ADR).
- `qwen2.5:3b-instruct`'s answer quality is lower than Gemini's — accepted for the default,
  since ADR-0008's citation-grounding gate and empirically-tuned relevance threshold are the
  actual safety backstop, not raw model quality; a smaller model that says "I don't know" more
  often is safer than requiring a cloud dependency to say anything at all.

## Alternatives Considered
- **Keep Gemini as the only provider**: rejected — the adoption/reliability cost described in
  Context is exactly what this ADR exists to fix.
- **Drop Gemini, local-only**: rejected — some self-hosters have a Google API key and would
  prefer better answer quality; ADR-0002's port-based design already makes supporting both
  nearly free, so there's no real cost to keeping Gemini available.
- **llama.cpp or ONNX Runtime instead of Ollama**: rejected — no first-class Spring AI starter
  for either (unlike Ollama, which has one that mirrors the existing Google GenAI starter
  pattern exactly), meaning more adapter code to write and maintain for no clear benefit at
  this project's scale.
