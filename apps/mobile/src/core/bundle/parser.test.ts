import { PUBLISHER_BUNDLE_JSON, PUBLISHER_DOCUMENT_ID, PUBLISHER_DOCUMENT_JSON } from '../testing/fixtures';
import { utf8Decode } from '../bytes';
import {
  BundleFormatError,
  documentFileChecksum,
  parseBundleManifest,
  parseDocumentFile,
  parseDocumentFileBytes,
  verifyDocumentFile,
} from './parser';
import { sha256Hex } from './sha256';

const manifest = parseBundleManifest(PUBLISHER_BUNDLE_JSON);
const document = manifest.documents[0];
const documentBytes = new TextEncoder().encode(PUBLISHER_DOCUMENT_JSON);

const messageOf = (fn: () => unknown): string => {
  try {
    fn();
  } catch (e) {
    expect(e).toBeInstanceOf(BundleFormatError);
    return (e as Error).message;
  }
  throw new Error('expected a BundleFormatError');
};

describe('bundle parser', () => {
  it('parses a manifest written by the Kotlin publisher', () => {
    expect(manifest.formatVersion).toBe(1);
    expect(manifest.bundleVersion).toBe('2026-10-05T12:00:00Z');
    expect(manifest.documents).toHaveLength(1);
    expect(document).toMatchObject({
      id: PUBLISHER_DOCUMENT_ID, title: 'Flood guide', agency: 'Example Agency', publishedDate: '2024-03-15',
      licence: 'CC-BY-4.0', country: 'NO', hazards: ['FLOOD'],
      sourceUri: 'https://example.org/fixture-flood-guide.pdf', sourceType: 'pdf',
    });
    expect(document.checksum).toHaveLength(64);
    expect(document.chunks.map((c) => c.id)).toEqual([`${PUBLISHER_DOCUMENT_ID}-0`, `${PUBLISHER_DOCUMENT_ID}-1`]);
  });

  it('matches the file checksum of the publisher file', () => {
    expect(documentFileChecksum(documentBytes)).toBe(document.fileChecksum);
    expect(() => verifyDocumentFile(document, documentBytes)).not.toThrow();
  });

  it('fails the file checksum on any change', () => {
    const tampered = new TextEncoder().encode(`${PUBLISHER_DOCUMENT_JSON} `);
    expect(messageOf(() => verifyDocumentFile(document, tampered))).toContain('does not match the manifest');
  });

  it('matches document chunks to the manifest', () => {
    const file = parseDocumentFileBytes(documentBytes);
    expect(file.id).toBe(document.id);
    expect(file.chunks.map((c) => c.id)).toEqual(document.chunks.map((c) => c.id));
    expect(file.chunks.map((c) => c.pageNumber)).toEqual([1, 2]);
    file.chunks.forEach((chunk, i) => {
      expect(sha256Hex(new TextEncoder().encode(chunk.content))).toBe(document.chunks[i].checksum);
    });
  });

  it('rejects an unsupported format version with a clear message', () => {
    const message = messageOf(() => parseBundleManifest(PUBLISHER_BUNDLE_JSON.replace('"formatVersion": 1', '"formatVersion": 2')));
    expect(message).toContain('Unsupported bundle formatVersion 2');
    expect(message).toContain('reads formatVersion 1');
  });

  it('rejects a missing format version', () => {
    expect(messageOf(() => parseBundleManifest(PUBLISHER_BUNDLE_JSON.replace('"formatVersion": 1,', '')))).toContain('(missing)');
  });

  it('rejects blank ids', () => {
    expect(
      messageOf(() => parseBundleManifest(PUBLISHER_BUNDLE_JSON.replace(`"id": "${PUBLISHER_DOCUMENT_ID}"`, '"id": " "'))),
    ).toContain('document id must not be blank');
    expect(
      messageOf(() => parseBundleManifest(PUBLISHER_BUNDLE_JSON.replace(`"${PUBLISHER_DOCUMENT_ID}-0"`, '""'))),
    ).toContain('chunk id');
    expect(
      messageOf(() => parseDocumentFile(PUBLISHER_DOCUMENT_JSON.replace(`"${PUBLISHER_DOCUMENT_ID}-1"`, '""'))),
    ).toContain('chunk id');
  });

  it('rejects malformed JSON and wrong shapes', () => {
    messageOf(() => parseBundleManifest('{'));
    messageOf(() => parseBundleManifest('[1, 2]'));
    messageOf(() => parseDocumentFile('{"id": "x"}'));
  });

  it('parses a null country and a null page number', () => {
    const parsed = parseBundleManifest(
      JSON.stringify({
        formatVersion: 1, bundleVersion: '2026-10-05T12:00:00Z',
        documents: [{
          id: 'abc', title: 'Global storm page', agency: 'Agency', publishedDate: '2025-01-02', licence: 'CC-BY-4.0',
          country: null, hazards: [], sourceUri: 'https://example.org/storm', sourceType: 'html', checksum: '00',
          fileChecksum: '00', chunks: [{ id: 'abc-0', checksum: '00' }],
        }],
      }),
    );
    expect(parsed.documents[0].country).toBeNull();
    const file = parseDocumentFile('{"id": "abc", "chunks": [{"id": "abc-0", "content": "Stay indoors.", "pageNumber": null}]}');
    expect(file.chunks[0].pageNumber).toBeNull();
    expect(file.chunks[0].content).toContain('Stay indoors.');
  });
});

describe('utf8Decode', () => {
  const cases = ['plain', 'ñandú', '日本語', 'emoji 🌋 here', 'mixed é日🌋'];
  it.each(cases)('round-trips %s', (text) => {
    expect(utf8Decode(new TextEncoder().encode(text))).toBe(text);
  });
  it('replaces invalid sequences with U+FFFD instead of throwing', () => {
    expect(utf8Decode(new Uint8Array([0x61, 0xff, 0x62, 0xc3]))).toBe('a�b�');
  });
});
