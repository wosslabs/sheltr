import { ed25519 } from '@noble/curves/ed25519.js';

/** Length in bytes of a raw Ed25519 public key. */
export const ED25519_PUBLIC_KEY_LENGTH = 32;

/** Length in bytes of an Ed25519 signature, as written to bundle.json.sig. */
export const ED25519_SIGNATURE_LENGTH = 64;

/**
 * Returns true only if [signature] is a valid Ed25519 signature of [message] under [publicKey] (32 raw bytes, not
 * DER). A key or signature of the wrong length, or any failure in the crypto library, returns false. Never throws
 * for bad input: false means the update must be rejected and the last good content kept.
 */
export function verifySignature(publicKey: Uint8Array, message: Uint8Array, signature: Uint8Array): boolean {
  if (publicKey.length !== ED25519_PUBLIC_KEY_LENGTH) return false;
  if (signature.length !== ED25519_SIGNATURE_LENGTH) return false;
  try {
    // zip215: false = strict RFC 8032 verification, like the JDK and CryptoKit verifiers.
    return ed25519.verify(signature, message, publicKey, { zip215: false });
  } catch {
    return false;
  }
}
