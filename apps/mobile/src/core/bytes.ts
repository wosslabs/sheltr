import { bytesToHex, hexToBytes } from '@noble/hashes/utils.js';

export { bytesToHex, hexToBytes };

/** Decodes UTF-8. Invalid sequences become U+FFFD. Does not depend on TextDecoder, which Hermes may lack. */
export function utf8Decode(bytes: Uint8Array): string {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i];
    let codePoint = 0xfffd;
    let length = 1;
    if (b0 < 0x80) {
      codePoint = b0;
    } else if (b0 >= 0xc2 && b0 <= 0xdf && continuation(bytes, i + 1)) {
      codePoint = ((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f);
      length = 2;
    } else if (b0 >= 0xe0 && b0 <= 0xef && continuation(bytes, i + 1) && continuation(bytes, i + 2)) {
      const cp = ((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f);
      if (cp >= 0x800 && (cp < 0xd800 || cp > 0xdfff)) {
        codePoint = cp;
        length = 3;
      }
    } else if (
      b0 >= 0xf0 && b0 <= 0xf4 &&
      continuation(bytes, i + 1) && continuation(bytes, i + 2) && continuation(bytes, i + 3)
    ) {
      const cp =
        ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
      if (cp >= 0x10000 && cp <= 0x10ffff) {
        codePoint = cp;
        length = 4;
      }
    }
    out += String.fromCodePoint(codePoint);
    i += length;
  }
  return out;
}

function continuation(bytes: Uint8Array, index: number): boolean {
  return index < bytes.length && (bytes[index] & 0xc0) === 0x80;
}
