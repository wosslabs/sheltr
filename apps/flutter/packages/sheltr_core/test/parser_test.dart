import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:sheltr_core/bundle/parser.dart';
import 'package:sheltr_core/bundle/sha256.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

String messageOf(void Function() fn) {
  try {
    fn();
  } on BundleFormatError catch (error) {
    return error.message;
  }
  throw StateError('expected a BundleFormatError');
}

void main() {
  final manifestText = File(
    '${fixtureDirectory('valid-signed').path}/bundle.json',
  ).readAsStringSync();
  final manifest = parseBundleManifest(manifestText);
  final document = manifest.documents[0];
  final documentBytes = File(
    '${fixtureDirectory('valid-signed').path}/documents/${document.id}.json',
  ).readAsBytesSync();

  test('sha256Hex matches the empty, abc, and padding vectors', () {
    expect(
      sha256Hex(Uint8List(0)),
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    );
    expect(
      sha256Hex(Uint8List.fromList(utf8.encode('abc'))),
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
    expect(
      sha256Hex(Uint8List(56)..fillRange(0, 56, 97)),
      'b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a',
    );
  });

  test('parses a signed manifest', () {
    expect(manifest.formatVersion, 1);
    expect(manifest.bundleVersion, '2026-11-02T22:33:03Z');
    expect(manifest.documents, hasLength(2));
    expect(document.id, '251b3d589b274399efbed685c3e6d68f');
    expect(document.title, 'Boil water');
    expect(document.agency, 'Example Agency');
    expect(document.publishedDate, '2024-03-15');
    expect(document.licence, 'CC-BY-4.0');
    expect(document.country, 'NO');
    expect(document.hazards, ['FLOOD']);
    expect(document.sourceUri, 'https://example.org/boil-water.html');
    expect(document.sourceType, 'html');
    expect(document.checksum, hasLength(64));
    expect(document.chunks.map((chunk) => chunk.id), ['${document.id}-0']);
  });

  test('matches the file checksum of the bytes on disk', () {
    expect(documentFileChecksum(documentBytes), document.fileChecksum);
    expect(() => verifyDocumentFile(document, documentBytes), returnsNormally);
  });

  test('fails the file checksum when the bytes change', () {
    final tampered = Uint8List.fromList([...documentBytes, 0x20]);
    expect(
      messageOf(() => verifyDocumentFile(document, tampered)),
      contains('does not match the manifest'),
    );
  });

  test('hashes the bytes read, not a re-encoded string', () {
    final reencoded = Uint8List.fromList(
      utf8.encode(jsonEncode(jsonDecode(utf8.decode(documentBytes)))),
    );
    expect(
      messageOf(() => verifyDocumentFile(document, reencoded)),
      contains('does not match the manifest'),
    );
  });

  test('rejects invalid UTF-8 document bytes', () {
    expect(
      messageOf(
        () => parseDocumentFileBytes(Uint8List.fromList([0xFF, 0xFE, 0xFD])),
      ),
      contains('not valid UTF-8'),
    );
  });

  test('matches document chunks to the manifest', () {
    final file = parseDocumentFileBytes(documentBytes);
    expect(file.id, document.id);
    expect(
      file.chunks.map((chunk) => chunk.id),
      document.chunks.map((chunk) => chunk.id),
    );
    expect(file.chunks.single.pageNumber, isNull);
    expect(
      sha256Hex(Uint8List.fromList(utf8.encode(file.chunks.single.content))),
      document.chunks.single.checksum,
    );
  });

  test('rejects an unsupported format version before reading the rest', () {
    final message = messageOf(
      () => parseBundleManifest(
        manifestText.replaceFirst('"formatVersion": 1', '"formatVersion": 2'),
      ),
    );
    expect(message, contains('Unsupported bundle formatVersion 2'));
    expect(message, contains('reads formatVersion 1'));
    expect(message, contains('format'));
  });

  test('rejects a missing format version', () {
    expect(
      messageOf(
        () => parseBundleManifest(
          manifestText.replaceFirst('"formatVersion": 1,', ''),
        ),
      ),
      contains('(missing)'),
    );
  });

  test('rejects blank ids', () {
    expect(
      messageOf(
        () => parseBundleManifest(
          manifestText.replaceFirst('"id": "${document.id}"', '"id": " "'),
        ),
      ),
      contains('document id must not be blank'),
    );
    expect(
      messageOf(
        () => parseBundleManifest(
          manifestText.replaceFirst('"${document.id}-0"', '""'),
        ),
      ),
      contains('chunk id'),
    );
    final fileText = utf8.decode(documentBytes);
    expect(
      messageOf(
        () => parseDocumentFile(
          fileText.replaceFirst('"${document.id}-0"', '""'),
        ),
      ),
      contains('chunk id'),
    );
  });

  test('rejects malformed JSON and wrong shapes', () {
    expect(messageOf(() => parseBundleManifest('{')), contains('JSON'));
    expect(messageOf(() => parseBundleManifest('[1, 2]')), contains('object'));
    expect(
      messageOf(() => parseDocumentFile('{"id": "x"}')),
      contains('array'),
    );
  });

  test('parses a null country and a null page number', () {
    final parsed = parseBundleManifest(
      jsonEncode({
        'formatVersion': 1,
        'bundleVersion': '2026-10-05T12:00:00Z',
        'documents': [
          {
            'id': 'abc',
            'title': 'Global storm page',
            'agency': 'Agency',
            'publishedDate': '2025-01-02',
            'licence': 'CC-BY-4.0',
            'country': null,
            'hazards': <String>[],
            'sourceUri': 'https://example.org/storm',
            'sourceType': 'html',
            'checksum': '00',
            'fileChecksum': '00',
            'chunks': [
              {'id': 'abc-0', 'checksum': '00'},
            ],
          },
        ],
      }),
    );
    expect(parsed.documents.single.country, isNull);

    final omitted = parseBundleManifest(
      jsonEncode({
        'formatVersion': 1,
        'bundleVersion': '2026-10-05T12:00:00Z',
        'documents': [
          {
            'id': 'abc',
            'title': 'Global storm page',
            'agency': 'Agency',
            'publishedDate': '2025-01-02',
            'licence': 'CC-BY-4.0',
            'hazards': <String>[],
            'sourceUri': 'https://example.org/storm',
            'sourceType': 'html',
            'checksum': '00',
            'fileChecksum': '00',
            'chunks': [
              {'id': 'abc-0', 'checksum': '00'},
            ],
          },
        ],
      }),
    );
    expect(omitted.documents.single.country, isNull);

    final file = parseDocumentFile(
      '{"id": "abc", "chunks": [{"id": "abc-0", "content": "Stay indoors.", "pageNumber": null}]}',
    );
    expect(file.chunks.single.pageNumber, isNull);
    expect(file.chunks.single.content, contains('Stay indoors.'));
  });

  test('rejects a page number that is not an integer', () {
    expect(
      messageOf(
        () => parseDocumentFile(
          '{"id": "abc", "chunks": [{"id": "abc-0", "content": "Stay indoors.", "pageNumber": 1.5}]}',
        ),
      ),
      contains('pageNumber'),
    );
  });
}
