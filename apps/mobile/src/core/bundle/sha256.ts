import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '../bytes';

/** Lower-case hex SHA-256 of [bytes]. */
export function sha256Hex(bytes: Uint8Array): string {
  return bytesToHex(sha256(bytes));
}
