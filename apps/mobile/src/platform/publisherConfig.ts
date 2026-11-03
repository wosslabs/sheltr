import Constants from 'expo-constants';
import { hexToBytes } from '../core/bytes';
import { compileTrustedKeys, type TrustedKey } from '../core/trustedKeys';

export interface PublisherConfig {
  readonly baseUrl: string;
  readonly publicKey: Uint8Array;
  readonly trustedKeys: readonly TrustedKey[];
}

/**
 * Where to fetch the signed bundle and the Ed25519 public key to verify it with, from app.config.js
 * (SHELTR_PUBLISHER_BASE_URL and SHELTR_PUBLISHER_PUBLIC_KEY_HEX at build time). Null when either is missing or the
 * key is not 32 bytes of hex: sync is then off and the app only has the bundled sample content.
 */
export function readPublisherConfig(extra: Record<string, unknown> | undefined = Constants.expoConfig?.extra): PublisherConfig | null {
  const baseUrl = typeof extra?.publisherBaseUrl === 'string' ? extra.publisherBaseUrl.trim() : '';
  const keyHex = typeof extra?.publisherPublicKeyHex === 'string' ? extra.publisherPublicKeyHex.trim() : '';
  if (baseUrl === '' || keyHex === '') return null;
  if (!/^[0-9a-fA-F]{64}$/.test(keyHex)) return null;
  const trustedKeys = readTrustedKeys(extra?.publisherTrustedKeys);
  if (trustedKeys === null) return null;
  return { baseUrl, publicKey: hexToBytes(keyHex.toLowerCase()), trustedKeys };
}

/** Empty or absent is no list. A present value that is not one or two valid keys is a missing config. */
function readTrustedKeys(raw: unknown): readonly TrustedKey[] | null {
  if (typeof raw !== 'string' || raw.trim() === '') return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return null;
    return compileTrustedKeys(parsed.map((item) => {
      if (typeof item !== 'object' || item === null) throw new Error('trusted key is not an object');
      const record = item as Record<string, unknown>;
      if (typeof record.id !== 'string' || typeof record.publicKeyHex !== 'string' || typeof record.notAfter !== 'string') {
        throw new Error('trusted key is missing a field');
      }
      return { id: record.id, publicKeyHex: record.publicKeyHex, notAfter: record.notAfter };
    }));
  } catch {
    return null;
  }
}
