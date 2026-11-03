import 'dart:io';
import 'dart:typed_data';

import 'package:sheltr_core/bundle/parser.dart';
import 'package:sheltr_core/signing/verifier.dart';
import 'package:test/test.dart';

import 'fixtures.dart';
import 'hex.dart';

Future<bool> signatureVerifies(String name) async {
  final dir = fixtureDirectory(name);
  final message = File('${dir.path}/bundle.json').readAsBytesSync();
  final signature = File('${dir.path}/bundle.json.sig').readAsBytesSync();
  final publicKey = hexToBytes(
    File('${dir.path}/public-key.hex').readAsStringSync(),
  );
  return verifySignature(
    publicKey,
    Uint8List.fromList(message),
    Uint8List.fromList(signature),
  );
}

void main() {
  test('valid-signed verifies', () async {
    expect(await signatureVerifies('valid-signed'), isTrue);
  });

  test('tampered-manifest does not verify', () async {
    expect(await signatureVerifies('tampered-manifest'), isFalse);
  });

  test('wrong-key does not verify', () async {
    expect(await signatureVerifies('wrong-key'), isFalse);
  });

  test('unsupported-format throws a message containing format', () async {
    expect(await signatureVerifies('unsupported-format'), isTrue);
    final text = File(
      '${fixtureDirectory('unsupported-format').path}/bundle.json',
    ).readAsStringSync();
    expect(
      () => parseBundleManifest(text),
      throwsA(
        predicate<BundleFormatError>(
          (error) => error.message.contains('format'),
        ),
      ),
    );
  });

  test(
    'bad-file-checksum throws a message containing does not match the manifest',
    () async {
      expect(await signatureVerifies('bad-file-checksum'), isTrue);
      final dir = fixtureDirectory('bad-file-checksum');
      final manifest = parseBundleManifest(
        File('${dir.path}/bundle.json').readAsStringSync(),
      );
      final failures = <BundleFormatError>[];
      for (final doc in manifest.documents) {
        final bytes = File('${dir.path}/documents/${doc.id}.json')
            .readAsBytesSync();
        try {
          verifyDocumentFile(doc, Uint8List.fromList(bytes));
        } on BundleFormatError catch (error) {
          failures.add(error);
        }
      }
      expect(failures, hasLength(1));
      expect(failures.single.message, contains('does not match the manifest'));
    },
  );
}
