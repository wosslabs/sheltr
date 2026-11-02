import type { SourceType } from './manifest.ts';
import { sha256Hex } from './sha256.ts';

/** The bytes produced for one source, with the SHA-256 (lower-case hex) of those bytes. */
export interface RawContent {
  readonly bytes: Uint8Array;
  readonly checksum: string;
}

export function rawContent(bytes: Uint8Array): RawContent {
  return { bytes, checksum: sha256Hex(bytes) };
}

/** Fetches one source and returns its content with a checksum. */
export interface SourceConnector {
  fetch(sourceUri: string): Promise<RawContent>;
}

export type ConnectorMap = Partial<Record<SourceType, SourceConnector>>;

/** Whole-request timeout (headers and body). */
export const REQUEST_TIMEOUT_MS = 60_000;

/** GET [url] without following redirects. Network failures become "Failed to download <uri>". */
export async function getOnce(url: string, sourceUri: string, timeoutMs: number): Promise<Response> {
  try {
    return await fetch(url, { method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    throw new Error(`Failed to download ${sourceUri}: ${(e as Error).message}`, { cause: e });
  }
}
