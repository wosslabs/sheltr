export interface StoredDocument {
  readonly id: string;
  readonly title: string;
  readonly agency: string;
  readonly publishedDate: string;
  readonly licence: string;
  readonly country: string | null;
  /** DisasterType names the document covers, sorted. Empty if the bundle lists none. */
  readonly hazards: readonly string[];
  readonly sourceUri: string;
  readonly sourceType: string;
  readonly checksum: string;
  readonly fileChecksum: string;
}

export interface StoredChunk {
  readonly id: string;
  readonly documentId: string;
  readonly content: string;
  readonly pageNumber: number | null;
  /** Index of the chunk within its document, from 0. */
  readonly position: number;
}

export interface SearchHit {
  readonly chunk: StoredChunk;
  /** FTS5 bm25 score: lower is a better match. */
  readonly score: number;
}

/** Read access to the active content. */
export interface StoreReader {
  /** The bundle version of the active content, or null if no content has been installed. */
  bundleVersion(): Promise<string | null>;
  document(id: string): Promise<StoredDocument | null>;
  /** Every document in the active content, ordered by title. Empty if no content is installed. */
  documents(): Promise<StoredDocument[]>;
  /** The chunks of one document in bundle order. */
  chunks(documentId: string): Promise<StoredChunk[]>;
  /**
   * Keyword search over chunk text (FTS5). [query] is user text: it is split into words and any word may match;
   * FTS5 syntax in it is not interpreted. Results are best first. Returns an empty list for a query with no words.
   */
  search(query: string, limit?: number): Promise<SearchHit[]>;
}
