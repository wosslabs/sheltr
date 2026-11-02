/*
 * The corpus bundle as the publisher writes it (tools/publisher, bundle/BundleFormat.kt and BundleBuilder.kt).
 * The field names and nullability must match the publisher exactly.
 *
 *   bundle.json          BundleManifest (signed by bundle.json.sig)
 *   documents/<id>.json  DocumentFile, one per document; BundleDocument.fileChecksum covers its exact bytes
 *
 * Every checksum is lower-case hex SHA-256.
 */

/** Version of the bundle layout. This app reads only this version. */
export const BUNDLE_FORMAT_VERSION = 1;

export const BUNDLE_MANIFEST_FILE = 'bundle.json';
export const BUNDLE_SIGNATURE_FILE = 'bundle.json.sig';
export const BUNDLE_DOCUMENTS_DIR = 'documents';

export interface BundleManifest {
  readonly formatVersion: number;
  /** UTC build time, ISO-8601 to the second, for example 2026-10-05T12:00:00Z. */
  readonly bundleVersion: string;
  readonly documents: readonly BundleDocument[];
}

export interface BundleDocument {
  /** First 32 hex characters of the SHA-256 of the source URI. Stable across builds. */
  readonly id: string;
  readonly title: string;
  readonly agency: string;
  /** ISO date, YYYY-MM-DD. */
  readonly publishedDate: string;
  readonly licence: string;
  /** ISO 3166-1 alpha-2 code, or null for global guidance. */
  readonly country: string | null;
  /** DisasterType names. */
  readonly hazards: readonly string[];
  readonly sourceUri: string;
  /** pdf or html. */
  readonly sourceType: string;
  /** The approved content checksum: SHA-256 of the PDF bytes, or of the extracted HTML text. */
  readonly checksum: string;
  /** SHA-256 of the exact bytes of documents/<id>.json. */
  readonly fileChecksum: string;
  readonly chunks: readonly BundleChunkRef[];
}

export interface BundleChunkRef {
  /** "<documentId>-<index>", index from 0. */
  readonly id: string;
  /** SHA-256 of the chunk content in UTF-8. */
  readonly checksum: string;
}

export interface DocumentFile {
  readonly id: string;
  readonly chunks: readonly DocumentChunk[];
}

export interface DocumentChunk {
  readonly id: string;
  readonly content: string;
  /** 1-based PDF page, or null for HTML. */
  readonly pageNumber: number | null;
}
