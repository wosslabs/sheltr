import { createHash } from 'node:crypto';

/** Lower-case hexadecimal SHA-256 of [bytes]. */
export function sha256Hex(bytes: Uint8Array | string): string {
  return createHash('sha256').update(bytes).digest('hex');
}
