import {
  createPrivateKey, createPublicKey, generateKeyPairSync, sign as nodeSign, verify as nodeVerify,
  type KeyObject,
} from 'node:crypto';
import { readFileSync } from 'node:fs';

/*
 * Ed25519 on Node's built-in crypto. Keys are PKCS#8 (private) and X.509 SubjectPublicKeyInfo (public), in DER or
 * PEM. The signature is the raw 64 bytes the app verifies.
 */
const SIGNATURE_LENGTH = 64;

/** Generates a new Ed25519 key pair. Used by tests. */
export function generateKeyPair(): { privateKey: KeyObject; publicKey: KeyObject } {
  return generateKeyPairSync('ed25519');
}

/** Signs [bytes] with [privateKey] and returns the 64-byte Ed25519 signature. */
export function sign(privateKey: KeyObject, bytes: Uint8Array): Uint8Array {
  return new Uint8Array(nodeSign(null, bytes, privateKey));
}

/**
 * True only if [signature] is a valid Ed25519 signature of [bytes] under [publicKey]. A signature of the wrong
 * length, or one the library rejects as malformed, returns false rather than throwing.
 */
export function verify(publicKey: KeyObject, bytes: Uint8Array, signature: Uint8Array): boolean {
  if (signature.length !== SIGNATURE_LENGTH) return false;
  try {
    return nodeVerify(null, bytes, publicKey, signature);
  } catch {
    return false;
  }
}

/** Loads a PKCS#8 Ed25519 private key from [path]. The caller chooses the path. */
export function loadPrivateKey(path: string): KeyObject {
  const key = createPrivateKey({ key: decodeKeyFile(path, 'PRIVATE KEY'), format: 'der', type: 'pkcs8' });
  if (key.asymmetricKeyType !== 'ed25519') throw new Error(`${path} is not an Ed25519 key`);
  return key;
}

/** Loads an X.509 Ed25519 public key from [path]. */
export function loadPublicKey(path: string): KeyObject {
  const key = createPublicKey({ key: decodeKeyFile(path, 'PUBLIC KEY'), format: 'der', type: 'spki' });
  if (key.asymmetricKeyType !== 'ed25519') throw new Error(`${path} is not an Ed25519 key`);
  return key;
}

/** Returns the DER bytes of a key file: raw DER, or the base64 body of a PEM block with the given label. */
function decodeKeyFile(path: string, label: string): Buffer {
  const bytes = readFileSync(path);
  if (bytes.length === 0 || bytes[0] !== '-'.charCodeAt(0)) return bytes;
  const text = bytes.toString('ascii');
  const begin = `-----BEGIN ${label}-----`;
  const end = `-----END ${label}-----`;
  const start = text.indexOf(begin);
  const stop = text.indexOf(end);
  if (start < 0 || stop <= start) throw new Error(`${path} is not a PEM '${label}' block`);
  return Buffer.from(text.slice(start + begin.length, stop).replace(/\s+/g, ''), 'base64');
}
