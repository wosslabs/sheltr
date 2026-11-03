# ADR-0017: On-device Gemma and a vector index in the content database

## Status
Accepted 2026-11-03.

## Context
Search was SQLite FTS5 over approved chunks. The LiteRT-LM adapter existed and `AppSettings.modelEnabled` stayed false. Keyword coverage on the 2018 IFRC guide also accepted off-topic questions whose words appear in annexes.

The product now needs a local model on the phone, and the same stack on this laptop. The model is Google LiteRT-LM with Gemma (https://developers.google.com/edge/litert-lm). The corpus has to stay in one file that a crash cannot half-apply.

## Decision
1. Chunk embeddings live in `chunk_vectors` inside `content.db`. A replacement is one SQLite transaction. A bundle install builds a new database and does not copy the old vectors, so the index cannot describe a corpus that is no longer installed.
2. Vectors come from EmbeddingGemma 2 Text 270M, file `embeddinggemma-2-text-270m.litertlm` (768 dimensions), through LiteRT-LM. Documents use `TaskType.retrievalDocument`. Questions use `TaskType.retrievalQuery`.
3. The lead sentence comes from Gemma 4 E2B, file `gemma-4-E2B-it.litertlm`, through the existing passage adapter. It may only restate quoted passages. A retrieval refusal does not call it. The support check is unchanged. The backend stays CPU. The context window is 2048 tokens and generation stops at 256 tokens. LiteRT-LM treats the old 256 value as the context window and rejects a prompt that does not fit.
4. Vector search is the answer path when the embedder is loaded. The gate is cosine similarity of at least 0.55. Country and hazard boosts only reorder. When the embedder is absent, keyword search stays. An embedder failure refuses. It does not fall back to keywords.
5. Both `.litertlm` files stay outside the repository. The Mac replica starts only with `--dart-define=SHELTR_LAPTOP=true` and `--dart-define=SHELTR_MODELS=<directory>`. `AppSettings.modelEnabled` stays false in the committed default until `docs/model-eval.md` records a pass on an Android phone and an iPhone.

## Consequences
- The phone and the laptop use one Flutter path. Chrome remains a UI test and does not run the native model file.
- The Mac target is macOS 14. Its sandbox is off so the replica can read the model directory outside the repo. Android and iOS keep their own sandbox.
- The first launch embeds every chunk. Later launches reuse the vectors when the count matches the chunks.
- The cosine floor is a starting gate, not a completed phone eval. A laptop run does not fill the eval table.
