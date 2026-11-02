import assert from 'node:assert/strict';
import { createPrivateKey, createPublicKey } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { generateKeyPair, loadPrivateKey, loadPublicKey, sign, verify } from './signing.ts';
import { scratchDir } from './testHelpers.ts';

const hex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'));

// RFC 8032 section 7.1, TEST 1: empty message.
const SEED = '9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60';
const PUBLIC = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a';
const SIGNATURE =
  'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b';
const rfcPrivate = createPrivateKey({ key: Buffer.from(`302e020100300506032b657004220420${SEED}`, 'hex'), format: 'der', type: 'pkcs8' });
const rfcPublic = createPublicKey({ key: Buffer.from(`302a300506032b6570032100${PUBLIC}`, 'hex'), format: 'der', type: 'spki' });

describe('Ed25519', () => {
  it('signs RFC 8032 test vector 1', () => assert.deepEqual(sign(rfcPrivate, new Uint8Array(0)), hex(SIGNATURE)));
  it('verifies RFC 8032 test vector 1', () => assert.ok(verify(rfcPublic, new Uint8Array(0), hex(SIGNATURE))));

  it('round-trips generated keys', () => {
    const { privateKey, publicKey } = generateKeyPair();
    const message = new TextEncoder().encode('bundle');
    assert.ok(verify(publicKey, message, sign(privateKey, message)));
  });

  it('fails for a tampered message', () => {
    const { privateKey, publicKey } = generateKeyPair();
    const signature = sign(privateKey, new TextEncoder().encode('bundle'));
    assert.ok(!verify(publicKey, new TextEncoder().encode('bundlf'), signature));
  });

  it('fails for the wrong public key', () => {
    const message = new TextEncoder().encode('bundle');
    const signature = sign(generateKeyPair().privateKey, message);
    assert.ok(!verify(generateKeyPair().publicKey, message, signature));
  });

  it('returns false, not an error, for a truncated or corrupted signature', () => {
    const signature = hex(SIGNATURE);
    assert.ok(!verify(rfcPublic, new Uint8Array(0), signature.slice(0, 63)));
    assert.ok(!verify(rfcPublic, new Uint8Array(0), new Uint8Array(65)));
    const corrupted = signature.slice();
    corrupted[10] ^= 1;
    assert.ok(!verify(rfcPublic, new Uint8Array(0), corrupted));
  });

  it('loads keys from PEM and DER files', () => {
    const { dir, cleanup } = scratchDir();
    try {
      const { privateKey, publicKey } = generateKeyPair();
      const privPem = join(dir, 'k.pem');
      const pubPem = join(dir, 'p.pem');
      const privDer = join(dir, 'k.der');
      const pubDer = join(dir, 'p.der');
      writeFileSync(privPem, privateKey.export({ format: 'pem', type: 'pkcs8' }));
      writeFileSync(pubPem, publicKey.export({ format: 'pem', type: 'spki' }));
      writeFileSync(privDer, privateKey.export({ format: 'der', type: 'pkcs8' }));
      writeFileSync(pubDer, publicKey.export({ format: 'der', type: 'spki' }));
      const message = new TextEncoder().encode('bundle');
      for (const [priv, pub] of [[privPem, pubPem], [privDer, pubDer]]) {
        assert.ok(verify(loadPublicKey(pub), message, sign(loadPrivateKey(priv), message)));
      }
    } finally {
      cleanup();
    }
  });

  it('rejects a PEM file with the wrong label', () => {
    const { dir, cleanup } = scratchDir();
    try {
      mkdirSync(dir, { recursive: true });
      const path = join(dir, 'k.pem');
      writeFileSync(path, generateKeyPair().publicKey.export({ format: 'pem', type: 'spki' }));
      assert.throws(() => loadPrivateKey(path), /is not a PEM 'PRIVATE KEY' block/);
    } finally {
      cleanup();
    }
  });
});
