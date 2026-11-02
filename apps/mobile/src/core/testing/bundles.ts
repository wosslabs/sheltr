import { ed25519 } from '@noble/curves/ed25519.js';
import {
  BUNDLE_FORMAT_VERSION, type BundleDocument, type BundleManifest, type DocumentFile,
} from '../bundle/format';
import { sha256Hex } from '../bundle/sha256';
import { ContentStore, type DocumentSource } from '../store/contentStore';
import { NodeSqliteDatabase } from './nodeSqlite';

const enc = (s: string) => new TextEncoder().encode(s);

export async function openStore(): Promise<{ store: ContentStore; db: NodeSqliteDatabase }> {
  const db = new NodeSqliteDatabase();
  return { store: await ContentStore.open(db), db };
}

/** A manifest entry and its document file, for store tests that do not need real checksums. */
export function doc(
  id: string,
  title: string,
  country: string | null,
  chunks: ReadonlyArray<readonly [string, number | null]>,
  hazards: string[] = ['FLOOD'],
): { entry: BundleDocument; file: DocumentFile } {
  const file: DocumentFile = {
    id,
    chunks: chunks.map(([content, pageNumber], i) => ({ id: `${id}-${i}`, content, pageNumber })),
  };
  const entry: BundleDocument = {
    id, title, agency: 'Example Agency', publishedDate: '2024-03-15', licence: 'CC-BY-4.0', country, hazards,
    sourceUri: `https://example.org/${id}`, sourceType: 'pdf', checksum: '0'.repeat(64), fileChecksum: '1'.repeat(64),
    chunks: file.chunks.map((c) => ({ id: c.id, checksum: '2'.repeat(64) })),
  };
  return { entry, file };
}

export interface FakeBundle {
  manifest: BundleManifest;
  source: DocumentSource;
}

export function bundle(version: string, ...docs: ReturnType<typeof doc>[]): FakeBundle {
  const byId = new Map(docs.map((d) => [d.entry.id, d.file]));
  return {
    manifest: { formatVersion: BUNDLE_FORMAT_VERSION, bundleVersion: version, documents: docs.map((d) => d.entry) },
    source: (document) => byId.get(document.id)!,
  };
}

/** A signed, serialisable bundle for sync tests, built the way the publisher builds one. */
export class Signer {
  private readonly secretKey: Uint8Array;
  readonly publicKey: Uint8Array;

  constructor() {
    const keys = ed25519.keygen();
    this.secretKey = keys.secretKey;
    this.publicKey = keys.publicKey;
  }

  sign(message: Uint8Array): Uint8Array {
    return ed25519.sign(message, this.secretKey);
  }
}

export function documentBytes(id: string, text: string): Uint8Array {
  return enc(JSON.stringify({ id, chunks: [{ id: `${id}-0`, content: text, pageNumber: 1 }] }, null, 4));
}

export class TestBundle {
  readonly files: Map<string, Uint8Array>;
  readonly manifest: BundleManifest;
  readonly manifestBytes: Uint8Array;
  readonly signature: Uint8Array;

  constructor(readonly version: string, documents: Record<string, string>, signer: Signer, formatVersion = BUNDLE_FORMAT_VERSION) {
    this.files = new Map(Object.entries(documents).map(([id, text]) => [id, documentBytes(id, text)]));
    this.manifest = {
      formatVersion,
      bundleVersion: version,
      documents: Object.entries(documents).map(([id, text]): BundleDocument => ({
        id, title: `Title ${id}`, agency: 'Example Agency', publishedDate: '2026-01-01', licence: 'CC-BY-4.0',
        country: 'NO', hazards: ['FLOOD'], sourceUri: `https://example.org/${id}.pdf`, sourceType: 'pdf',
        checksum: sha256Hex(enc(`source ${id} ${text}`)),
        fileChecksum: sha256Hex(this.files.get(id)!),
        chunks: [{ id: `${id}-0`, checksum: sha256Hex(enc(text)) }],
      })),
    };
    this.manifestBytes = enc(JSON.stringify(this.manifest, null, 4));
    this.signature = signer.sign(this.manifestBytes);
  }
}
