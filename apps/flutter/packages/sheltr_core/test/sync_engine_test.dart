import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/dart.dart';
import 'package:sheltr_core/bundle/format.dart';
import 'package:sheltr_core/bundle/parser.dart';
import 'package:sheltr_core/signing/trusted_keys.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:sheltr_core/sync/client.dart';
import 'package:sheltr_core/sync/engine.dart';
import 'package:sheltr_core/sync/result.dart';
import 'package:sheltr_core/sync/retry_policy.dart';
import 'package:test/test.dart';

import 'fixtures.dart';
import 'hex.dart';

void main() {
  final names = _fixtureNames();

  for (final name in names) {
    test(name, () async {
      await _expectFixture(name);
    });
  }

  test('an installed bundle version is up to date', () async {
    final directory = fixtureDirectory('valid-signed');
    final publicKey = hexToBytes(
      File('${directory.path}/public-key.hex').readAsStringSync(),
    );
    const baseUrl = 'https://example.org/bundle';
    final storeDirectory = Directory.systemTemp.createTempSync('sheltr-sync-');
    final store = ContentStore.open(storeDirectory.path);
    try {
      var now = 1000;
      final engine = SyncEngine(
        clientFor: _InMemoryBundle(directory).clientFor,
        nowEpochSeconds: () => now,
      );
      final first = await engine.run(baseUrl, publicKey, store);
      expect(first, isA<SyncUpdated>());
      expect((first as SyncUpdated).documentsDownloaded, 2);
      expect(store.lastSyncEpochSeconds(), 1000);

      now = 2000;
      final second = await engine.run(baseUrl, publicKey, store);
      expect(second, isA<SyncUpToDate>());
      expect((second as SyncUpToDate).bundleVersion, first.bundleVersion);
      expect(store.bundleVersion(), first.bundleVersion);
      expect(store.lastSyncEpochSeconds(), 2000);
    } finally {
      store.close();
      storeDirectory.deleteSync(recursive: true);
    }
  });

  group('RetryPolicy', () {
    const failed = SyncFailed(reason: 'connection reset');

    test('doubles delays from the base', () {
      final policy = RetryPolicy(
        maxAttempts: 5,
        baseDelaySeconds: 30,
        maxDelaySeconds: 3600,
      );
      expect(
        [
          for (final n in [1, 2, 3, 4]) policy.delayBeforeRetry(n, failed),
        ],
        [30, 60, 120, 240],
      );
    });

    test('caps the delay and does not overflow', () {
      final policy = RetryPolicy(
        maxAttempts: 100,
        baseDelaySeconds: 30,
        maxDelaySeconds: 100,
      );
      expect(policy.delayBeforeRetry(2, failed), 60);
      expect(policy.delayBeforeRetry(3, failed), 100);
      expect(policy.delayBeforeRetry(99, failed), 100);
    });

    test('stops once attempts are used up', () {
      final policy = RetryPolicy(maxAttempts: 3);
      expect(policy.delayBeforeRetry(2, failed), 60);
      expect(policy.delayBeforeRetry(3, failed), isNull);
      expect(policy.delayBeforeRetry(4, failed), isNull);
    });

    test('returns null for rejectedSignature, upToDate, and updated', () {
      final policy = RetryPolicy();
      expect(policy.delayBeforeRetry(1, const SyncRejectedSignature()), isNull);
      expect(
        policy.delayBeforeRetry(1, const SyncUpToDate(bundleVersion: 'v')),
        isNull,
      );
      expect(
        policy.delayBeforeRetry(
          1,
          const SyncUpdated(bundleVersion: 'v', documentsDownloaded: 1),
        ),
        isNull,
      );
    });

    test('never retries with a single attempt', () {
      expect(RetryPolicy(maxAttempts: 1).delayBeforeRetry(1, failed), isNull);
    });

    test('rejects invalid policies', () {
      expect(() => RetryPolicy(maxAttempts: 0), throwsRangeError);
      expect(() => RetryPolicy(baseDelaySeconds: 0), throwsRangeError);
      expect(
        () => RetryPolicy(baseDelaySeconds: 60, maxDelaySeconds: 30),
        throwsRangeError,
      );
      expect(() => RetryPolicy().delayBeforeRetry(0, failed), throwsRangeError);
    });
  });

  test('refuses a document id that could change the path', () {
    var called = false;
    final client = FetchSyncClient(
      'https://example.org/bundle',
      fetch: (url) async {
        called = true;
        return SyncResponse(ok: true, status: 200, bytes: Uint8List(0));
      },
    );
    expect(
      client.fetchDocumentFile('../secret'),
      throwsA(
        isA<SyncHttpError>().having(
          (error) => error.message,
          'message',
          contains('unexpected characters'),
        ),
      ),
    );
    expect(called, isFalse);
  });

  test('model body stream is not drained after the byte cap', () async {
    final directory = Directory.systemTemp.createTempSync('sheltr-sync-');
    final store = ContentStore.open(directory.path);
    var pulls = 0;
    var drained = false;
    var bufferedCalls = 0;
    try {
      Stream<List<int>> body() async* {
        pulls += 1;
        yield [1, 2, 3, 4];
        pulls += 1;
        yield [5, 6];
        drained = true;
        pulls += 1;
        yield [7, 8, 9];
      }

      final client = FetchSyncClient(
        'https://example.org/bundle',
        fetch: (url) async {
          bufferedCalls += 1;
          return SyncResponse(
            ok: true,
            status: 200,
            bytes: Uint8List.fromList([1, 2, 3, 4, 5, 6, 7, 8, 9]),
          );
        },
        streamFetch: (url) async {
          expect(url, 'https://example.org/bundle/models/passage.bin');
          return SyncStreamResponse(ok: true, status: 200, body: body());
        },
      );
      final model = BundleModel(
        file: 'models/passage.bin',
        sha256: 'ab' * 32,
        byteSize: 4,
        modelType: 'gemma4',
      );
      expect(
        await store.commitModelStream(
          client.fetchBundleFile(model.file),
          model,
        ),
        isFalse,
      );
      expect(pulls, 2);
      expect(drained, isFalse);
      expect(bufferedCalls, 0);
      expect(File('${directory.path}/verified-model').existsSync(), isFalse);
      expect(
        File('${directory.path}/verified-model.staging').existsSync(),
        isFalse,
      );
    } finally {
      store.close();
      directory.deleteSync(recursive: true);
    }
  });

  test('installed model throw keeps an up to date content result', () async {
    final fixture = fixtureDirectory('valid-signed');
    final manifest = jsonDecode(
      File('${fixture.path}/bundle.json').readAsStringSync(),
    ) as Map<String, Object?>;
    manifest['model'] = {
      'file': 'models/passage.bin',
      'sha256': 'ab' * 32,
      'byteSize': 4,
      'modelType': 'gemma4',
    };
    final manifestBytes = Uint8List.fromList(utf8.encode(jsonEncode(manifest)));
    final algorithm = DartEd25519();
    final keyPair = await algorithm.newKeyPair();
    final signed = await algorithm.sign(manifestBytes, keyPair: keyPair);
    final signature = Uint8List.fromList(signed.bytes);
    final publicKey = Uint8List.fromList(
      (await keyPair.extractPublicKey()).bytes,
    );
    final documents = <String, Uint8List>{};
    for (final item in manifest['documents']! as List<Object?>) {
      final id = (item! as Map)['id'] as String;
      documents[id] = File('${fixture.path}/documents/$id.json')
          .readAsBytesSync();
    }

    const baseUrl = 'https://example.org/bundle';
    final storeDirectory = Directory.systemTemp.createTempSync('sheltr-sync-');
    final store = ContentStore.open(storeDirectory.path);
    try {
      final engine = SyncEngine(
        clientFor: (url) => FetchSyncClient(
          url,
          fetch: (request) => _fixtureResponse(
            baseUrl,
            request,
            manifestBytes: manifestBytes,
            signature: signature,
            documents: documents,
          ),
          streamFetch: (request) async {
            return SyncStreamResponse(
              ok: false,
              status: 404,
              body: Stream<List<int>>.empty(),
            );
          },
        ),
      );
      final first = await engine.run(baseUrl, publicKey, store);
      expect(first, isA<SyncUpdated>());

      final meta = File('${storeDirectory.path}/model.json');
      final sha = 'ab' * 32;
      meta.writeAsStringSync(
        '{"sha256":"$sha","byteSize":4,"modelType":"gemma4"}',
      );
      File('${storeDirectory.path}/verified-model')
          .writeAsBytesSync(const [1, 2, 3, 4]);
      Process.runSync('chmod', ['000', meta.path]);
      expect(store.installedModel, throwsA(isA<FileSystemException>()));

      final second = await engine.run(baseUrl, publicKey, store);
      expect(second, isA<SyncUpToDate>());
      expect((second as SyncUpToDate).bundleVersion, manifest['bundleVersion']);
      expect(store.bundleVersion(), manifest['bundleVersion']);
      expect(store.sequence(), manifest['sequence']);
      expect(store.documents(), hasLength(2));
    } finally {
      final meta = File('${storeDirectory.path}/model.json');
      if (meta.existsSync()) {
        Process.runSync('chmod', ['644', meta.path]);
      }
      store.close();
      storeDirectory.deleteSync(recursive: true);
    }
  });
}

Future<void> _expectFixture(String name) async {
  final directory = fixtureDirectory(name);
  final spec = jsonDecode(
    File('${directory.path}/expect.json').readAsStringSync(),
  ) as Map<String, Object?>;
  final prior = spec['prior']! as String;
  final resultKind = spec['result']! as String;
  final reasonContains = spec['reasonContains'] as String?;
  final documentsDownloaded = spec['documentsDownloaded'];
  final baseUrlFile = File('${directory.path}/base-url.txt');
  final baseUrl = baseUrlFile.existsSync()
      ? baseUrlFile.readAsStringSync().trim()
      : 'https://example.org/bundle';
  final publicKey = hexToBytes(
    File('${directory.path}/public-key.hex').readAsStringSync(),
  );

  final storeDirectory = Directory.systemTemp.createTempSync('sheltr-sync-');
  final store = ContentStore.open(storeDirectory.path);
  try {
    if (prior != 'empty') {
      _installFixture(store, prior);
    }
    final host = _InMemoryBundle(directory);
    final engine = SyncEngine(clientFor: host.clientFor);
    final result = await engine.run(
      baseUrl,
      publicKey,
      store,
      trustedKeys: _trustedKeys(directory),
    );

    expect(result.kind, resultKind);
    if (reasonContains != null) {
      expect(result, isA<SyncFailed>());
      expect((result as SyncFailed).reason, contains(reasonContains));
    }
    if (documentsDownloaded is num) {
      expect(result, isA<SyncUpdated>());
      expect((result as SyncUpdated).documentsDownloaded, documentsDownloaded);
    }
  } finally {
    store.close();
    storeDirectory.deleteSync(recursive: true);
  }
}

List<TrustedKey>? _trustedKeys(Directory directory) {
  final file = File('${directory.path}/trusted-keys.json');
  if (!file.existsSync()) return null;
  final decoded = jsonDecode(file.readAsStringSync()) as List<Object?>;
  return [
    for (final item in decoded)
      TrustedKey(
        id: (item! as Map)['id'] as String,
        publicKeyHex: (item as Map)['publicKeyHex'] as String,
        notAfter: (item as Map)['notAfter'] as String,
      ),
  ];
}

void _installFixture(ContentStore store, String name) {
  final directory = fixtureDirectory(name);
  final manifest = parseBundleManifest(
    File('${directory.path}/bundle.json').readAsStringSync(),
  );
  final files = {
    for (final document in manifest.documents)
      document.id: parseDocumentFileBytes(
        File('${directory.path}/documents/${document.id}.json')
            .readAsBytesSync(),
      ),
  };
  store.install(manifest, (document) => files[document.id]!);
}

List<String> _fixtureNames() {
  // The loop lists every contract/fixtures directory. These are reached only
  // that way: downgrade-older-sequence, missing-document, plain-http-url,
  // same-sequence, valid-unchanged-doc.
  final root = fixtureDirectory('valid-signed').parent;
  final names = [
    for (final entity in root.listSync())
      if (entity is Directory) _directoryName(entity),
  ]..sort();
  return names;
}

String _directoryName(Directory directory) {
  final parts = directory.uri.pathSegments.where((part) => part.isNotEmpty);
  return parts.last;
}

Future<SyncResponse> _fixtureResponse(
  String baseUrl,
  String url, {
  required Uint8List manifestBytes,
  required Uint8List signature,
  required Map<String, Uint8List> documents,
}) async {
  final base = baseUrl.replaceAll(RegExp(r'/+$'), '');
  final prefix = '$base/';
  if (!url.startsWith(prefix)) {
    return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
  }
  final relative = url.substring(prefix.length);
  if (relative == bundleManifestFile) {
    return SyncResponse(ok: true, status: 200, bytes: manifestBytes);
  }
  if (relative == bundleSignatureFile) {
    return SyncResponse(ok: true, status: 200, bytes: signature);
  }
  const documentsPrefix = '$bundleDocumentsDir/';
  if (relative.startsWith(documentsPrefix) && relative.endsWith('.json')) {
    final id = relative.substring(
      documentsPrefix.length,
      relative.length - '.json'.length,
    );
    final bytes = documents[id];
    if (bytes != null) {
      return SyncResponse(ok: true, status: 200, bytes: bytes);
    }
  }
  return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
}

/// Serves one fixture directory as bundle files. Reads local bytes only.
class _InMemoryBundle {
  _InMemoryBundle(this.directory);

  final Directory directory;

  SyncClient clientFor(String baseUrl) {
    return FetchSyncClient(baseUrl, fetch: (url) => _fetch(baseUrl, url));
  }

  Future<SyncResponse> _fetch(String baseUrl, String url) async {
    final base = baseUrl.replaceAll(RegExp(r'/+$'), '');
    final prefix = '$base/';
    if (!url.startsWith(prefix)) {
      return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
    }
    final relative = url.substring(prefix.length);
    final file = File('${directory.path}/$relative');
    if (!file.existsSync()) {
      return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
    }
    return SyncResponse(ok: true, status: 200, bytes: file.readAsBytesSync());
  }
}
