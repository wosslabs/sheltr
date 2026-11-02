import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { chunkPages, chunkUnpaged, type ChunkDraft } from './chunker.ts';
import type { ConnectorMap } from './connector.ts';
import type { CorpusManifest, ManifestDocument } from './manifest.ts';
import { extractPdfPages } from './pdf.ts';
import { rejectionMessage, type SourcePolicy } from './policy.ts';
import { sha256Hex } from './sha256.ts';
import { loadPrivateKey, sign } from './signing.ts';
import { clean, pageText } from './text.ts';

/*
 * The corpus bundle as written to disk:
 *
 *   <out>/bundle.json          BundleManifest (signed)
 *   <out>/bundle.json.sig      raw 64-byte Ed25519 signature of the exact bytes of bundle.json
 *   <out>/documents/<id>.json  DocumentFile, one per document
 *
 * Every checksum is lower-case hex SHA-256. The app (apps/mobile/src/core/bundle) reads exactly this layout.
 * Field order matters: the JSON is written with four-space indentation and explicit nulls, byte for byte as the
 * app's tests expect.
 */

/** Version of the bundle layout. Raise it on any incompatible change. */
export const BUNDLE_FORMAT_VERSION = 1;
export const BUNDLE_MANIFEST_FILE = 'bundle.json';
export const BUNDLE_SIGNATURE_FILE = 'bundle.json.sig';
export const BUNDLE_DOCUMENTS_DIR = 'documents';

export interface BundleManifest {
  formatVersion: number;
  /** UTC build time, ISO-8601 to the second, for example 2026-10-05T12:00:00Z. */
  bundleVersion: string;
  documents: BundleDocument[];
}

export interface BundleDocument {
  /** First 32 hex characters of the SHA-256 of the source URI. Stable across builds. */
  id: string;
  title: string;
  agency: string;
  /** ISO date, YYYY-MM-DD. */
  publishedDate: string;
  licence: string;
  /** ISO 3166-1 alpha-2 code, or null for global guidance. */
  country: string | null;
  /** DisasterType names. */
  hazards: string[];
  sourceUri: string;
  /** pdf or html. */
  sourceType: string;
  /** The approved content checksum: SHA-256 of the PDF bytes, or of the extracted HTML text. */
  checksum: string;
  /** SHA-256 of the exact bytes of documents/<id>.json, so the signature covers the document files too. */
  fileChecksum: string;
  chunks: { id: string; checksum: string }[];
}

export interface DocumentFile {
  id: string;
  chunks: { id: string; content: string; pageNumber: number | null }[];
}

/** Serialises a bundle file: four-space indentation, explicit nulls, UTF-8, no trailing newline. */
export function encodeBundleJson(value: BundleManifest | DocumentFile): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value, null, 4));
}

/** One manifest entry that was left out of the bundle, and why. */
export interface EntryFailure {
  readonly sourceUri: string;
  readonly reason: string;
}

/** The outcome of a build that produced a bundle. */
export interface BuildResult {
  readonly outputDir: string;
  readonly manifest: BundleManifest;
  readonly failures: readonly EntryFailure[];
}

/** Thrown when no bundle is produced. Nothing is left at the output path. */
export class BundleBuildError extends Error {
  readonly failures: readonly EntryFailure[];
  constructor(message: string, failures: readonly EntryFailure[] = [], options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'BundleBuildError';
    this.failures = failures;
  }
}

/** First 32 hex characters of the SHA-256 of [sourceUri]. Stable for the same URI. */
export function documentId(sourceUri: string): string {
  return sha256Hex(sourceUri).slice(0, 32);
}

const errorMessage = (e: unknown): string => (e instanceof Error ? e.message || e.name : String(e));

/**
 * Builds a signed corpus bundle from an approved manifest. Every entry in the manifest is approved.
 *
 * Per entry: check the source policy, fetch with the connector for its source type, compare the approved checksum
 * (case-insensitive) with the content checksum, then extract (PDF), clean and chunk. An entry that fails any step
 * is reported in the result's failures and left out; the other entries still build.
 *
 * The bundle is written to a temporary sibling of the output directory and renamed into place only after it is
 * complete and signed. If the build fails (unreadable key, output already present, no entry built, I/O error), the
 * temporary directory is removed and nothing exists at the output path.
 */
export async function buildBundle(
  manifest: CorpusManifest,
  connectors: ConnectorMap,
  policy: SourcePolicy,
  outputDir: string,
  privateKeyPath: string,
  now: () => Date = () => new Date(),
): Promise<BuildResult> {
  const output = resolve(outputDir);
  if (existsSync(output)) throw new BundleBuildError(`Output already exists: ${output}`);
  // Fail fast, before any download. The key is read only from the path the caller gives.
  let privateKey;
  try {
    privateKey = loadPrivateKey(privateKeyPath);
  } catch (e) {
    throw new BundleBuildError(`Cannot read the signing key at ${privateKeyPath}`, [], { cause: e });
  }
  const parent = dirname(output);
  mkdirSync(parent, { recursive: true });
  const staging = mkdtempSync(join(parent, `.${basename(output)}.tmp-${randomBytes(2).toString('hex')}-`));
  let moved = false;
  try {
    const documentsDir = join(staging, BUNDLE_DOCUMENTS_DIR);
    mkdirSync(documentsDir);
    const documents: BundleDocument[] = [];
    const failures: EntryFailure[] = [];
    for (const entry of manifest.documents) {
      let chunks: ChunkDraft[];
      try {
        chunks = await chunksFor(entry, connectors, policy);
      } catch (e) {
        failures.push({ sourceUri: entry.sourceUri, reason: errorMessage(e) });
        continue;
      }
      documents.push(writeDocument(entry, chunks, documentsDir));
    }
    if (documents.length === 0) throw new BundleBuildError('No document built; nothing to publish', failures);

    const bundle: BundleManifest = {
      formatVersion: BUNDLE_FORMAT_VERSION,
      bundleVersion: now().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      documents,
    };
    const bundleBytes = encodeBundleJson(bundle);
    writeFileSync(join(staging, BUNDLE_MANIFEST_FILE), bundleBytes);
    writeFileSync(join(staging, BUNDLE_SIGNATURE_FILE), sign(privateKey, bundleBytes));
    if (existsSync(output)) throw new BundleBuildError(`Output already exists: ${output}`);
    renameSync(staging, output);
    moved = true;
    return { outputDir: output, manifest: bundle, failures };
  } finally {
    if (!moved) rmSync(staging, { recursive: true, force: true });
  }
}

async function chunksFor(entry: ManifestDocument, connectors: ConnectorMap, policy: SourcePolicy): Promise<ChunkDraft[]> {
  const uri = entry.sourceUri;
  if (!policy.allows(uri)) throw new Error(rejectionMessage(uri));
  const connector = connectors[entry.sourceType];
  if (connector === undefined) {
    throw new Error(`Unsupported source type ${entry.sourceType}, supported: ${Object.keys(connectors).join(', ')}`);
  }
  const raw = await connector.fetch(uri);
  if (entry.checksum.toLowerCase() !== raw.checksum.toLowerCase()) {
    throw new Error(
      `Checksum mismatch for ${uri}: approved ${entry.checksum} but downloaded ${raw.checksum}. ` +
        'The document changed since approval and needs re-approval.',
    );
  }
  let chunks: ChunkDraft[];
  if (entry.sourceType === 'html') {
    chunks = chunkUnpaged(clean(new TextDecoder().decode(raw.bytes)));
  } else {
    const pages = await extractPdfPages(raw.bytes);
    chunks = chunkPages(pages.map((p) => pageText(p.pageNumber, clean(p.text))));
  }
  if (chunks.length === 0) {
    const hint = entry.sourceType === 'pdf' ? ' Scanned or image-only PDFs are not supported.' : ' The page has no main text.';
    throw new Error(`No extractable text in ${uri}.${hint}`);
  }
  return chunks;
}

function writeDocument(entry: ManifestDocument, drafts: readonly ChunkDraft[], dir: string): BundleDocument {
  const id = documentId(entry.sourceUri);
  const chunks = drafts.map((d, index) => ({ id: `${id}-${index}`, content: d.content, pageNumber: d.pageNumber }));
  const fileBytes = encodeBundleJson({ id, chunks });
  writeFileSync(join(dir, `${id}.json`), fileBytes);
  return {
    id,
    title: entry.title,
    agency: entry.sourceAgency,
    publishedDate: entry.publishedDate,
    licence: entry.license,
    country: entry.country,
    hazards: [...entry.disasterTypes],
    sourceUri: entry.sourceUri,
    sourceType: entry.sourceType,
    checksum: entry.checksum.toLowerCase(),
    fileChecksum: sha256Hex(fileBytes),
    chunks: chunks.map((c) => ({ id: c.id, checksum: sha256Hex(c.content) })),
  };
}
