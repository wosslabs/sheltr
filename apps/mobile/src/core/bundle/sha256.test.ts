import { sha256Hex } from './sha256';

const bytes = (s: string) => new TextEncoder().encode(s);

describe('sha256Hex', () => {
  it('hashes empty input', () => {
    expect(sha256Hex(new Uint8Array(0))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  });
  it('hashes abc', () => {
    expect(sha256Hex(bytes('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });
  it('handles 56 bytes, which need an extra padding block', () => {
    expect(sha256Hex(new Uint8Array(56).fill(97))).toBe('b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a');
  });
  it('hashes a million a', () => {
    expect(sha256Hex(new Uint8Array(1_000_000).fill(97))).toBe(
      'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0',
    );
  });
});
