# ADR-0008: Citation-grounded generation is mandatory and structurally enforced

## Status
Accepted

## Context
Sheltr answers disaster-response questions. An invented (hallucinated)
answer about water safety, medication storage, or evacuation is a
safety hazard, not just a quality bug. Relying solely on a prompt
instruction ("always cite sources") is not a strong enough guarantee —
LLMs can and do ignore prompt instructions under some conditions.

## Decision
The AI context's `AnswerGenerator` domain service enforces this in code,
not just in the prompt: if retrieval (Search context) returns zero chunks
above a relevance threshold, `AnswerGenerator` returns a `NoAnswerAvailable`
result and never invokes the LLM to produce a free-form answer. Every
`Message` with role `ASSISTANT` that does reach a user carries a non-empty
`citations: List<Citation>`.

## Consequences
- A "no relevant guidance found" response is an explicit, testable domain
  outcome, not an LLM-dependent behavior.
- Answer-confidence scoring (`AnswerConfidence`) and citation attachment
  are unit-testable against the domain service directly, without calling
  Gemini in the test.
- Some genuinely answerable questions may get a "no answer" response if
  retrieval's relevance threshold is miscalibrated — this is an accepted
  tradeoff (a wrong "I don't know" is far safer than a wrong invented
  fact) and the threshold is tunable.

## Alternatives Considered
- **Prompt-only grounding instruction**: rejected as the sole mechanism —
  not verifiable/testable, and not a strong enough guarantee for
  safety-critical content.
