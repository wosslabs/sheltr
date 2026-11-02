import { BUNDLE_FORMAT_VERSION, type BundleDocument, type BundleManifest, type DocumentFile } from '../bundle/format';
import type { SqlDatabase, SqlExecutor } from './db';
import { ftsQueryFor } from './ftsQuery';
import { SCHEMA } from './schema';
import type { SearchHit, StoredChunk, StoredDocument, StoreReader } from './types';

/** Supplies the verified document file for one manifest entry. */
export type DocumentSource = (document: BundleDocument) => Promise<DocumentFile> | DocumentFile;

export class StoreUpdateError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'StoreUpdateError';
  }
}

interface DocumentRow {
  id: string;
  title: string;
  agency: string;
  published_date: string;
  licence: string;
  country: string | null;
  source_uri: string;
  source_type: string;
  checksum: string;
  file_checksum: string;
}

interface ChunkRow {
  id: string;
  document_id: string;
  content: string;
  page_number: number | null;
  position: number;
}

/**
 * The on-device content: one SQLite database holding one complete bundle.
 *
 * {@link install} replaces all content inside a single transaction and checks the new rows against the manifest
 * before committing. Any failure rolls the transaction back, so the previous content stays active and unchanged.
 *
 * Callers serialise {@link install} with reads (the sync job and the UI share one instance).
 */
export class ContentStore implements StoreReader {
  private constructor(private readonly db: SqlDatabase) {}

  /** Creates the tables if they are missing and returns a store over [db]. */
  static async open(db: SqlDatabase): Promise<ContentStore> {
    await db.exec(SCHEMA);
    return new ContentStore(db);
  }

  async bundleVersion(): Promise<string | null> {
    const rows = await this.db.all<{ bundle_version: string }>('SELECT bundle_version FROM schema_version WHERE id = 1');
    return rows[0]?.bundle_version ?? null;
  }

  async document(id: string): Promise<StoredDocument | null> {
    const rows = await this.db.all<DocumentRow>('SELECT * FROM documents WHERE id = ?', [id]);
    if (rows.length === 0) return null;
    const hazards = await this.db.all<{ hazard: string }>(
      'SELECT hazard FROM document_hazards WHERE document_id = ? ORDER BY hazard',
      [id],
    );
    return toDocument(rows[0], hazards.map((h) => h.hazard));
  }

  async documents(): Promise<StoredDocument[]> {
    const rows = await this.db.all<DocumentRow>('SELECT * FROM documents ORDER BY title, id');
    const hazardRows = await this.db.all<{ document_id: string; hazard: string }>(
      'SELECT document_id, hazard FROM document_hazards ORDER BY document_id, hazard',
    );
    const byDocument = new Map<string, string[]>();
    for (const { document_id, hazard } of hazardRows) {
      byDocument.set(document_id, [...(byDocument.get(document_id) ?? []), hazard]);
    }
    return rows.map((row) => toDocument(row, byDocument.get(row.id) ?? []));
  }

  async chunks(documentId: string): Promise<StoredChunk[]> {
    const rows = await this.db.all<ChunkRow>('SELECT * FROM chunks WHERE document_id = ? ORDER BY position', [
      documentId,
    ]);
    return rows.map(toChunk);
  }

  async search(query: string, limit = 20): Promise<SearchHit[]> {
    if (!(limit > 0)) throw new RangeError('limit must be positive');
    const match = ftsQueryFor(query);
    if (match === null) return [];
    // Lower score is a better match (FTS5 bm25). The query is built by ftsQueryFor, never from raw user text.
    const rows = await this.db.all<ChunkRow & { score: number }>(
      `SELECT chunks.id, chunks.document_id, chunks.content, chunks.page_number, chunks.position,
              bm25(chunks_fts) AS score
       FROM chunks_fts
       JOIN chunks ON chunks.id = chunks_fts.chunk_id
       WHERE chunks_fts MATCH ?
       ORDER BY score
       LIMIT ?`,
      [match, limit],
    );
    return rows.map((row) => ({ chunk: toChunk(row), score: row.score }));
  }

  /** UTC epoch seconds of the last sync that ended Updated or UpToDate, or null if none has (or no content). */
  async lastSyncEpochSeconds(): Promise<number | null> {
    const rows = await this.db.all<{ last_sync_epoch_seconds: number | null }>(
      'SELECT last_sync_epoch_seconds FROM schema_version WHERE id = 1',
    );
    return rows[0]?.last_sync_epoch_seconds ?? null;
  }

  /** Records a successful sync. Throws {@link StoreUpdateError} if no content is installed. */
  async recordSync(epochSeconds: number): Promise<void> {
    if ((await this.bundleVersion()) === null) {
      throw new StoreUpdateError('no content installed to record a sync on');
    }
    await this.db.run('UPDATE schema_version SET last_sync_epoch_seconds = ? WHERE id = 1', [epochSeconds]);
  }

  /**
   * Replaces the active content with the bundle described by [manifest], reading each document's chunks from
   * [documents]. All-or-nothing: on any failure, including a mismatch between the stored rows and the manifest,
   * the previous content stays active and unchanged and the error is thrown.
   */
  async install(manifest: BundleManifest, documents: DocumentSource): Promise<void> {
    if (manifest.formatVersion !== BUNDLE_FORMAT_VERSION) {
      throw new StoreUpdateError(`unsupported bundle format ${manifest.formatVersion}`);
    }
    // Load every document file first: a slow or failing source must not hold the write transaction open.
    const files = new Map<string, DocumentFile>();
    for (const doc of manifest.documents) {
      const file = await documents(doc);
      if (file.id !== doc.id) {
        throw new StoreUpdateError(`document file ${file.id} does not match manifest entry ${doc.id}`);
      }
      files.set(doc.id, file);
    }
    await this.db.transaction(async (tx) => {
      await clearContent(tx);
      await insertContent(tx, manifest, files);
      await verifyContent(tx, manifest);
    });
  }
}

async function clearContent(tx: SqlExecutor): Promise<void> {
  await tx.exec(`
    DELETE FROM chunks;
    DELETE FROM document_hazards;
    DELETE FROM documents;
    DELETE FROM schema_version;
  `);
}

async function insertContent(
  tx: SqlExecutor,
  manifest: BundleManifest,
  files: ReadonlyMap<string, DocumentFile>,
): Promise<void> {
  for (const doc of manifest.documents) {
    await tx.run(
      `INSERT INTO documents(id, title, agency, published_date, licence, country, source_uri, source_type, checksum, file_checksum)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [doc.id, doc.title, doc.agency, doc.publishedDate, doc.licence, doc.country, doc.sourceUri, doc.sourceType, doc.checksum, doc.fileChecksum],
    );
    for (const hazard of new Set(doc.hazards)) {
      await tx.run('INSERT INTO document_hazards(document_id, hazard) VALUES (?, ?)', [doc.id, hazard]);
    }
    const file = files.get(doc.id)!;
    for (const [position, chunk] of file.chunks.entries()) {
      await tx.run('INSERT INTO chunks(id, document_id, content, page_number, position) VALUES (?, ?, ?, ?, ?)', [
        chunk.id, doc.id, chunk.content, chunk.pageNumber, position,
      ]);
    }
  }
  await tx.run('INSERT INTO schema_version(id, bundle_version, format_version) VALUES (1, ?, ?)', [
    manifest.bundleVersion,
    manifest.formatVersion,
  ]);
}

/** Checks the rows just written against the manifest; throwing here rolls the whole install back. */
async function verifyContent(tx: SqlExecutor, manifest: BundleManifest): Promise<void> {
  const count = async (table: string): Promise<number> =>
    (await tx.all<{ n: number }>(`SELECT count(*) AS n FROM ${table}`))[0].n;
  const expectedDocuments = manifest.documents.length;
  const expectedChunks = manifest.documents.reduce((sum, d) => sum + d.chunks.length, 0);
  const expectedHazards = manifest.documents.reduce((sum, d) => sum + new Set(d.hazards).size, 0);

  const documents = await count('documents');
  const chunks = await count('chunks');
  const hazards = await count('document_hazards');
  const indexed = await count('chunks_fts');
  if (documents !== expectedDocuments) {
    throw new StoreUpdateError(`staged ${documents} documents, manifest lists ${expectedDocuments}`);
  }
  if (chunks !== expectedChunks) {
    throw new StoreUpdateError(`staged ${chunks} chunks, manifest lists ${expectedChunks}`);
  }
  if (hazards !== expectedHazards) {
    throw new StoreUpdateError(`staged ${hazards} document hazards, manifest lists ${expectedHazards}`);
  }
  if (indexed !== chunks) {
    throw new StoreUpdateError(`keyword index has ${indexed} rows for ${chunks} chunks`);
  }
}

function toDocument(row: DocumentRow, hazards: string[]): StoredDocument {
  return {
    id: row.id,
    title: row.title,
    agency: row.agency,
    publishedDate: row.published_date,
    licence: row.licence,
    country: row.country,
    hazards,
    sourceUri: row.source_uri,
    sourceType: row.source_type,
    checksum: row.checksum,
    fileChecksum: row.file_checksum,
  };
}

function toChunk(row: ChunkRow): StoredChunk {
  return {
    id: row.id,
    documentId: row.document_id,
    content: row.content,
    pageNumber: row.page_number,
    position: row.position,
  };
}
