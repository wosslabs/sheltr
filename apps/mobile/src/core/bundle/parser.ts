import { utf8Decode } from '../bytes';
import {
  BUNDLE_FORMAT_VERSION,
  type BundleChunkRef,
  type BundleDocument,
  type BundleManifest,
  type DocumentChunk,
  type DocumentFile,
} from './format';
import { sha256Hex } from './sha256';

/** A bundle file that does not follow the format: unsupported version, blank id, malformed JSON, bad checksum. */
export class BundleFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BundleFormatError';
  }
}

type Json = Record<string, unknown>;

/**
 * Parses bundle.json. Rejects any formatVersion other than {@link BUNDLE_FORMAT_VERSION} before the rest of the
 * file is read, so a future layout gets a clear message instead of a field error. Rejects blank ids.
 */
export function parseBundleManifest(text: string): BundleManifest {
  const root = parseObject(text, 'bundle.json');
  if (root.formatVersion !== BUNDLE_FORMAT_VERSION) {
    const shown = root.formatVersion === undefined ? '(missing)' : String(root.formatVersion);
    throw new BundleFormatError(
      `Unsupported bundle formatVersion ${shown}; this app reads formatVersion ${BUNDLE_FORMAT_VERSION} only.`,
    );
  }
  const documents = array(root.documents, 'bundle.json documents').map((raw, i) =>
    parseBundleDocument(object(raw, `documents[${i}]`)),
  );
  return {
    formatVersion: BUNDLE_FORMAT_VERSION,
    bundleVersion: string(root.bundleVersion, 'bundleVersion'),
    documents,
  };
}

function parseBundleDocument(raw: Json): BundleDocument {
  const id = notBlank(string(raw.id, 'document id'), 'document id');
  const chunks = array(raw.chunks, `document ${id} chunks`).map((c): BundleChunkRef => {
    const chunk = object(c, `chunk of document ${id}`);
    return {
      id: notBlank(string(chunk.id, 'chunk id'), `chunk id in document ${id}`),
      checksum: string(chunk.checksum, 'chunk checksum'),
    };
  });
  return {
    id,
    title: string(raw.title, 'title'),
    agency: string(raw.agency, 'agency'),
    publishedDate: string(raw.publishedDate, 'publishedDate'),
    licence: string(raw.licence, 'licence'),
    country: nullableString(raw.country, 'country'),
    hazards: array(raw.hazards, 'hazards').map((h) => string(h, 'hazard')),
    sourceUri: string(raw.sourceUri, 'sourceUri'),
    sourceType: string(raw.sourceType, 'sourceType'),
    checksum: string(raw.checksum, 'checksum'),
    fileChecksum: string(raw.fileChecksum, 'fileChecksum'),
    chunks,
  };
}

/** Parses documents/<id>.json from its text. */
export function parseDocumentFile(text: string): DocumentFile {
  const root = parseObject(text, 'document file');
  const id = notBlank(string(root.id, 'document file id'), 'document file id');
  const chunks = array(root.chunks, 'document file chunks').map((c): DocumentChunk => {
    const chunk = object(c, `chunk of document ${id}`);
    const pageNumber = chunk.pageNumber;
    if (pageNumber !== null && pageNumber !== undefined && !Number.isInteger(pageNumber)) {
      throw new BundleFormatError(`pageNumber of a chunk in document ${id} must be an integer or null.`);
    }
    return {
      id: notBlank(string(chunk.id, 'chunk id'), `chunk id in document ${id}`),
      content: string(chunk.content, 'chunk content'),
      pageNumber: (pageNumber as number | null | undefined) ?? null,
    };
  });
  return { id, chunks };
}

/** Parses documents/<id>.json from the exact bytes read from disk or the network (UTF-8). */
export function parseDocumentFileBytes(bytes: Uint8Array): DocumentFile {
  return parseDocumentFile(utf8Decode(bytes));
}

/**
 * The fileChecksum rule: SHA-256 (lower-case hex) of the exact bytes of documents/<id>.json, as stored. Compute it
 * over the bytes read, never over a re-encoded string.
 */
export function documentFileChecksum(fileBytes: Uint8Array): string {
  return sha256Hex(fileBytes);
}

/** Throws {@link BundleFormatError} unless [fileBytes] are the bytes the manifest entry [document] describes. */
export function verifyDocumentFile(document: BundleDocument, fileBytes: Uint8Array): void {
  const actual = documentFileChecksum(fileBytes);
  if (actual !== document.fileChecksum) {
    throw new BundleFormatError(
      `Document file ${document.id} does not match the manifest: expected fileChecksum ${document.fileChecksum}, got ${actual}.`,
    );
  }
}

function parseObject(text: string, what: string): Json {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (e) {
    throw new BundleFormatError(`${what} is not valid JSON: ${(e as Error).message}`);
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new BundleFormatError(`${what} must be a JSON object`);
  }
  return value as Json;
}

function object(value: unknown, what: string): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new BundleFormatError(`${what} must be an object.`);
  }
  return value as Json;
}

function array(value: unknown, what: string): unknown[] {
  if (!Array.isArray(value)) throw new BundleFormatError(`${what} must be an array.`);
  return value;
}

function string(value: unknown, what: string): string {
  if (typeof value !== 'string') throw new BundleFormatError(`${what} must be a string.`);
  return value;
}

function nullableString(value: unknown, what: string): string | null {
  if (value === null || value === undefined) return null;
  return string(value, what);
}

function notBlank(value: string, what: string): string {
  if (value.trim() === '') throw new BundleFormatError(`A ${what} must not be blank.`);
  return value;
}
