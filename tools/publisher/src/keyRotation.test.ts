import assert from 'node:assert/strict';
import { type KeyObject } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { buildBundle, type BundleManifest } from './bundle.ts';
import { rawContent, type ConnectorMap } from './connector.ts';
import { parseManifest } from './manifest.ts';
import { hostAllowlistPolicy } from './policy.ts';
import { sha256Hex } from './sha256.ts';
import { generateKeyPair, verify } from './signing.ts';
import { scratchDir } from './testHelpers.ts';

/**
 * Key id is the lower-case SHA-256 of the raw 32-byte Ed25519 public key.
 * The compiled list holds at most two of those keys.
 */
function rawPublicKeyHex(publicKey: KeyObject): string {
  const jwk = publicKey.export({ format: 'jwk' });
  if (jwk.x === undefined) throw new Error('Ed25519 public key is missing x');
  return Buffer.from(jwk.x, 'base64url').toString('hex');
}

function expectedKeyId(publicKey: KeyObject): string {
  return sha256Hex(Buffer.from(rawPublicKeyHex(publicKey), 'hex'));
}

const TEXT = 'Boil water for one minute before drinking.';
const URI = 'https://example.org/boil-water.html';
const fixedNow = () => new Date('2026-10-05T12:00:00.000Z');

async function signedBundle(dir: string, keyPath: string): Promise<{ manifest: BundleManifest; bytes: Buffer; signature: Buffer }> {
  const bytes = new TextEncoder().encode(TEXT);
  const yaml = `documents:
  - source_uri: ${URI}
    source_type: html
    title: Boil water
    source_agency: Example Agency
    hazards: [FLOOD]
    published_date: 2024-03-15
    reviewed_on: 2026-10-05
    expires_on: 2027-11-02
    reviewed_by: corpus maintainer
    license: CC-BY-4.0
    country: "NO"
    checksum: ${sha256Hex(bytes)}
`;
  const connectors: ConnectorMap = {
    html: { fetch: async () => rawContent(bytes) },
  };
  const result = await buildBundle(
    parseManifest(yaml),
    connectors,
    hostAllowlistPolicy(['example.org']),
    join(dir, 'bundle'),
    keyPath,
    fixedNow,
  );
  const bundleBytes = readFileSync(join(dir, 'bundle', 'bundle.json'));
  const signature = readFileSync(join(dir, 'bundle', 'bundle.json.sig'));
  return { manifest: result.manifest, bytes: bundleBytes, signature };
}

describe('key rotation', () => {
  it('rotatedKeyBundleVerifies', async () => {
    const scratch = scratchDir();
    const previous = generateKeyPair();
    const rotated = generateKeyPair();
    const extra = generateKeyPair();
    const keyPath = join(scratch.dir, 'rotated.pem');
    writeFileSync(keyPath, rotated.privateKey.export({ format: 'pem', type: 'pkcs8' }));
    try {
      const built = await signedBundle(scratch.dir, keyPath);
      const rotatedId = expectedKeyId(rotated.publicKey);
      assert.equal(built.manifest.signedBy, rotatedId);
      assert.notEqual(expectedKeyId(previous.publicKey), rotatedId);
      assert.ok(verify(rotated.publicKey, built.bytes, built.signature));

      const { compileTrustedKeys, decideTrustedKey } = await import('./trustedKeys.ts');
      const list = compileTrustedKeys([
        { id: expectedKeyId(previous.publicKey), publicKeyHex: rawPublicKeyHex(previous.publicKey), notAfter: '2027-11-02' },
        { id: rotatedId, publicKeyHex: rawPublicKeyHex(rotated.publicKey), notAfter: '2028-11-02' },
      ]);
      assert.equal(list.length, 2);
      assert.throws(
        () => compileTrustedKeys([
          ...list,
          { id: expectedKeyId(extra.publicKey), publicKeyHex: rawPublicKeyHex(extra.publicKey), notAfter: '2029-01-01' },
        ]),
        /at most two/,
      );
      const decision = decideTrustedKey(rotatedId, list, '2026-10-05');
      assert.equal(decision.kind, 'accept');
      if (decision.kind === 'accept') {
        assert.equal(Buffer.from(decision.publicKey).toString('hex'), rawPublicKeyHex(rotated.publicKey));
        assert.equal(decision.datePassed, false);
      }
    } finally {
      scratch.cleanup();
    }
  });

  it('unknownKeyIdRejected', async () => {
    const scratch = scratchDir();
    const current = generateKeyPair();
    const removed = generateKeyPair();
    const keyPath = join(scratch.dir, 'removed.pem');
    writeFileSync(keyPath, removed.privateKey.export({ format: 'pem', type: 'pkcs8' }));
    try {
      const built = await signedBundle(scratch.dir, keyPath);
      assert.ok(verify(removed.publicKey, built.bytes, built.signature));
      const { compileTrustedKeys, decideTrustedKey } = await import('./trustedKeys.ts');
      const list = compileTrustedKeys([
        { id: expectedKeyId(current.publicKey), publicKeyHex: rawPublicKeyHex(current.publicKey), notAfter: '2028-11-02' },
      ]);
      assert.equal(decideTrustedKey(built.manifest.signedBy, list, '2026-11-02').kind, 'rejectedSignature');
      assert.equal(decideTrustedKey('not-a-listed-key', list, '2026-11-02').kind, 'rejectedSignature');
      assert.equal(decideTrustedKey(null, list, '2026-11-02').kind, 'rejectedSignature');
    } finally {
      scratch.cleanup();
    }
  });

  it('listedKeyPastNotAfterStillInstallsAndWarns', async () => {
    const scratch = scratchDir();
    const keys = generateKeyPair();
    const keyPath = join(scratch.dir, 'key.pem');
    writeFileSync(keyPath, keys.privateKey.export({ format: 'pem', type: 'pkcs8' }));
    try {
      const built = await signedBundle(scratch.dir, keyPath);
      assert.ok(verify(keys.publicKey, built.bytes, built.signature));
      const { compileTrustedKeys, decideTrustedKey, keyDatePassed } = await import('./trustedKeys.ts');
      const id = expectedKeyId(keys.publicKey);
      const past = compileTrustedKeys([
        { id, publicKeyHex: rawPublicKeyHex(keys.publicKey), notAfter: '2020-01-01' },
      ]);
      const decision = decideTrustedKey(id, past, '2026-11-02');
      assert.equal(decision.kind, 'accept');
      if (decision.kind === 'accept') assert.equal(decision.datePassed, true);
      assert.equal(keyDatePassed(past, '2026-11-02'), true);
      const today = compileTrustedKeys([
        { id, publicKeyHex: rawPublicKeyHex(keys.publicKey), notAfter: '2026-11-02' },
      ]);
      assert.equal(keyDatePassed(today, '2026-11-02'), false);
    } finally {
      scratch.cleanup();
    }
  });
});
