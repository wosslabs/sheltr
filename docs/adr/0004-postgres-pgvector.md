# ADR-0004: PostgreSQL + pgvector for the vector store

Status: Superseded by ADR-0015 where it describes the server (see that ADR).

## Status
Accepted

## Context
Need a vector store for chunk embeddings supporting semantic + hybrid
search. Options range from dedicated vector databases (Pinecone, Weaviate,
Milvus) to a Postgres extension (pgvector).

## Decision
Use PostgreSQL with the `pgvector` extension. Embeddings live in the same
database as document/chunk metadata (`knowledge.chunks.embedding` as
`VECTOR(768)`).

## Consequences
- One operational system instead of two — no separate vector DB to deploy,
  monitor, or keep consistent with relational metadata.
- Transactional consistency: a chunk's metadata and embedding are written
  in the same transaction.
- pgvector's ANN indexes (HNSW) are good but not best-in-class at extreme
  scale/QPS compared to dedicated vector DBs — acceptable given the corpus
  is government guidance documents (thousands, not billions, of chunks).
- Revisit if corpus size or query volume outgrows pgvector's practical
  ceiling.

## Alternatives Considered
- **Pinecone/Weaviate/Milvus**: rejected for now — added operational
  surface (another system to run/monitor/secure) not justified at this
  corpus scale.
