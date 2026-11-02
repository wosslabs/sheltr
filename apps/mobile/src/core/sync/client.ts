import { BUNDLE_DOCUMENTS_DIR, BUNDLE_MANIFEST_FILE, BUNDLE_SIGNATURE_FILE } from '../bundle/format';

/** Fetches the files of one published bundle. Every call returns the exact bytes served, or throws. */
export interface SyncClient {
  /** bundle.json. */
  fetchManifestBytes(): Promise<Uint8Array>;
  /** bundle.json.sig (raw 64-byte Ed25519 signature). */
  fetchSignatureBytes(): Promise<Uint8Array>;
  /** documents/<id>.json. */
  fetchDocumentFile(id: string): Promise<Uint8Array>;
}

/** Thrown for a base URL that is not https or a response that is not a success. */
export class SyncHttpError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SyncHttpError';
  }
}

export type FetchLike = (url: string) => Promise<{ ok: boolean; status: number; arrayBuffer(): Promise<ArrayBuffer> }>;

/**
 * SyncClient over fetch. baseUrl is the bundle directory, for example https://example.org/bundle; it must be
 * https (plain http is refused, so content cannot be read or replaced in transit before the signature check).
 */
export class FetchSyncClient implements SyncClient {
  private readonly base: string;

  constructor(
    baseUrl: string,
    private readonly fetchFn: FetchLike = (url) => fetch(url),
  ) {
    this.base = requireHttps(baseUrl).replace(/\/+$/, '');
  }

  fetchManifestBytes(): Promise<Uint8Array> {
    return this.fetchBytes(BUNDLE_MANIFEST_FILE);
  }

  fetchSignatureBytes(): Promise<Uint8Array> {
    return this.fetchBytes(BUNDLE_SIGNATURE_FILE);
  }

  async fetchDocumentFile(id: string): Promise<Uint8Array> {
    // Ids are hex from the publisher; refuse anything that could change the path.
    if (!/^[\p{L}\p{N}_-]+$/u.test(id)) {
      throw new SyncHttpError(`refusing document id with unexpected characters: ${id}`);
    }
    return this.fetchBytes(`${BUNDLE_DOCUMENTS_DIR}/${id}.json`);
  }

  private async fetchBytes(path: string): Promise<Uint8Array> {
    const response = await this.fetchFn(`${this.base}/${path}`);
    if (!response.ok) throw new SyncHttpError(`GET ${path} returned ${response.status}`);
    return new Uint8Array(await response.arrayBuffer());
  }
}

/** Returns baseUrl if it is an https URL; throws SyncHttpError otherwise. */
export function requireHttps(baseUrl: string): string {
  if (!/^https:\/\//i.test(baseUrl) || baseUrl.length <= 'https://'.length) {
    throw new SyncHttpError(`bundle base URL must use https: ${baseUrl}`);
  }
  return baseUrl;
}
