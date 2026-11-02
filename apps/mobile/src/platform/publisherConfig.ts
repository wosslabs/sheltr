import Constants from 'expo-constants';
import { hexToBytes } from '../core/bytes';

export interface PublisherConfig {
  readonly baseUrl: string;
  readonly publicKey: Uint8Array;
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
  return { baseUrl, publicKey: hexToBytes(keyHex.toLowerCase()) };
}
