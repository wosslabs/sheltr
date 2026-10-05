# ADR-0013: Corpus defined by an approved manifest of official documents

Status: Accepted
Date: 2026-10-04

## Context

The seed rows in migration V5 are hand-written sentences attributed to real agencies. They
were cited to users as sources. Ingestion took its title, agency, and hazards from the
connector, so it could not load documents from more than one publisher.

## Decision

- The corpus is the list in `corpus/manifest.yaml`. Each entry is an official English
  document of global guidance, with its publisher, hazards, published date, reuse terms,
  and SHA-256 checksum. The maintainer approves every entry.
- `make ingest-corpus` loads entries not yet present. A checksum mismatch fails the entry:
  the document changed since approval.
- Ingestion refuses a document without recorded reuse terms (`license`). The manifest parser
  rejects an entry missing any required field.
- Related hazards are listed in the same file and must cite a corpus document. The parser
  rejects a related hazard that cites a URI not in the manifest.
- Seed rows have status `SEED` (set by migration V7). Retrieval returns only statuses in
  `sheltr.search.statuses`, which is `ACTIVE` in every profile and `ACTIVE,SEED` only in the
  test resources.
- No guidance text written by a person or a model is stored as corpus content.

## Current state

`corpus/manifest.yaml` is empty: it lists no documents and no related hazards. No document
has been approved under this process yet. Until the maintainer adds entries and runs
`make ingest-corpus`, the process loads nothing new.

## Consequences

- Anyone can rebuild the same corpus from the repository.
- Scanned or image-only PDFs cannot be ingested. Ingestion fails when a document yields no
  text.
- Retired and superseded documents are no longer retrievable; before this change retrieval
  ignored document status.
