import { hexToBytes } from './bytes';
import { verifySignature } from './signing';
import { SIGNED_BUNDLE_JSON, SIGNED_BUNDLE_PUBLIC_KEY_HEX, SIGNED_BUNDLE_SIGNATURE_HEX } from './testing/fixtures';

// RFC 8032 section 7.1, TEST 1: empty message.
const rfcPublicKey = hexToBytes('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a');
const rfcMessage = new Uint8Array(0);
const rfcSignature = hexToBytes(
  'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b',
);
// RFC 8032 section 7.1, TEST 2: a different key pair, used as the wrong key.
const otherPublicKey = hexToBytes('3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c');

const flip = (bytes: Uint8Array, index: number): Uint8Array => {
  const copy = bytes.slice();
  copy[index] ^= 0x01;
  return copy;
};

describe('verifySignature', () => {
  it('verifies RFC 8032 test vector 1', () => {
    expect(rfcPublicKey).toHaveLength(32);
    expect(rfcSignature).toHaveLength(64);
    expect(verifySignature(rfcPublicKey, rfcMessage, rfcSignature)).toBe(true);
  });

  it('fails for a tampered message', () => {
    expect(verifySignature(rfcPublicKey, new Uint8Array([0]), rfcSignature)).toBe(false);
    expect(verifySignature(rfcPublicKey, new TextEncoder().encode('x'), rfcSignature)).toBe(false);
  });

  it('fails for the wrong key', () => {
    expect(verifySignature(otherPublicKey, rfcMessage, rfcSignature)).toBe(false);
    expect(verifySignature(flip(rfcPublicKey, 0), rfcMessage, rfcSignature)).toBe(false);
  });

  it('fails for a truncated or corrupted signature', () => {
    expect(verifySignature(rfcPublicKey, rfcMessage, rfcSignature.slice(0, 63))).toBe(false);
    expect(verifySignature(rfcPublicKey, rfcMessage, new Uint8Array(65))).toBe(false);
    expect(verifySignature(rfcPublicKey, rfcMessage, new Uint8Array(0))).toBe(false);
    for (const index of [0, 31, 32, 63]) {
      expect(verifySignature(rfcPublicKey, rfcMessage, flip(rfcSignature, index))).toBe(false);
    }
    // S >= L (non-canonical scalar) must be rejected, not accepted or thrown.
    const highS = rfcSignature.slice();
    highS.fill(0xff, 32, 64);
    expect(verifySignature(rfcPublicKey, rfcMessage, highS)).toBe(false);
  });

  it('returns false instead of throwing for bad key input', () => {
    expect(verifySignature(new Uint8Array(0), rfcMessage, rfcSignature)).toBe(false);
    expect(verifySignature(rfcPublicKey.slice(0, 31), rfcMessage, rfcSignature)).toBe(false);
    expect(verifySignature(new Uint8Array(33), rfcMessage, rfcSignature)).toBe(false);
    // 44-byte X.509 DER is not accepted: the function takes the 32 raw bytes only.
    const der = new Uint8Array([...hexToBytes('302a300506032b6570032100'), ...rfcPublicKey]);
    expect(verifySignature(der, rfcMessage, rfcSignature)).toBe(false);
    // A 32-byte value that is not a valid curve point.
    expect(verifySignature(new Uint8Array(32).fill(0xff), rfcMessage, rfcSignature)).toBe(false);
  });

  describe('a bundle signed by the Kotlin publisher', () => {
    const bundle = new TextEncoder().encode(SIGNED_BUNDLE_JSON);
    const publicKey = hexToBytes(SIGNED_BUNDLE_PUBLIC_KEY_HEX);
    const signature = hexToBytes(SIGNED_BUNDLE_SIGNATURE_HEX);

    it('verifies', () => expect(verifySignature(publicKey, bundle, signature)).toBe(true));

    it('fails when one byte changes or the last byte is dropped', () => {
      for (const index of [0, Math.floor(bundle.length / 2), bundle.length - 1]) {
        expect(verifySignature(publicKey, flip(bundle, index), signature)).toBe(false);
      }
      expect(verifySignature(publicKey, bundle.slice(0, bundle.length - 1), signature)).toBe(false);
    });

    it('fails under another key', () => expect(verifySignature(rfcPublicKey, bundle, signature)).toBe(false));
  });
});
