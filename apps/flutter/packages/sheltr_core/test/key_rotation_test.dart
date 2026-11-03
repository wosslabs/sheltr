import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:sheltr_core/signing/trusted_keys.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:sheltr_core/sync/client.dart';
import 'package:sheltr_core/sync/engine.dart';
import 'package:sheltr_core/sync/result.dart';
import 'package:test/test.dart';

import 'fixtures.dart';
import 'hex.dart';

void main() {
  test('rotatedKeyBundleVerifies', () async {
    final keys = _trustedKeys('rotated-key-valid');
    final signedBy = _signedBy('rotated-key-valid');
    final other = keys.firstWhere((key) => key.id != signedBy);
    expect(keys.where((key) => key.id == signedBy), hasLength(1));
    expect(keys, hasLength(2));

    final store = _openStore();
    try {
      final result = await _sync(
        'rotated-key-valid',
        hexToBytes(other.publicKeyHex),
        keys,
        store,
      );
      expect(result, isA<SyncUpdated>());
      expect(
        () => compileTrustedKeys([
          ...keys,
          TrustedKey(
            id: 'c' * 64,
            publicKeyHex: 'ab' * 32,
            notAfter: '2029-01-01',
          ),
        ]),
        throwsArgumentError,
      );
    } finally {
      store.close();
    }
  });

  test('unknownKeyIdRejected', () async {
    final keys = _trustedKeys('revoked-key');
    final signedBy = _signedBy('revoked-key');
    expect(keys.any((key) => key.id == signedBy), isFalse);

    final store = _openStore();
    try {
      final result = await _sync(
        'revoked-key',
        _publicKey('revoked-key'),
        keys,
        store,
      );
      expect(result, isA<SyncRejectedSignature>());
    } finally {
      store.close();
    }
  });

  test('listedKeyPastNotAfterStillInstallsAndWarns', () async {
    final signedBy = _signedBy('rotated-key-valid');
    final keys = [
      for (final key in _trustedKeys('rotated-key-valid'))
        TrustedKey(
          id: key.id,
          publicKeyHex: key.publicKeyHex,
          notAfter: '2020-01-01',
        ),
    ];
    final other = keys.firstWhere((key) => key.id != signedBy);
    final store = _openStore();
    try {
      final result = await _sync(
        'rotated-key-valid',
        hexToBytes(other.publicKeyHex),
        keys,
        store,
      );
      expect(result, isA<SyncUpdated>());
      expect(keyDatePassed(keys, '2026-11-02'), isTrue);
      expect(
        keyDatePassed([
          for (final key in keys)
            TrustedKey(
              id: key.id,
              publicKeyHex: key.publicKeyHex,
              notAfter: '2026-11-02',
            ),
        ], '2026-11-02'),
        isFalse,
      );

      final boil = store.documents().where(
        (document) => document.title == 'Boil water',
      );
      expect(boil, isNotEmpty);
      expect(
        store
            .chunks(boil.first.id)
            .any((chunk) => chunk.content.contains('Boil water')),
        isTrue,
      );
    } finally {
      store.close();
    }
  });
}

ContentStore _openStore() {
  final directory = Directory.systemTemp.createTempSync('sheltr-keys-');
  addTearDown(() => directory.deleteSync(recursive: true));
  return ContentStore.open(directory);
}

Future<SyncResult> _sync(
  String name,
  Uint8List publicKey,
  List<TrustedKey> keys,
  ContentStore store,
) {
  final engine = SyncEngine(
    clientFor: _FixtureHost(fixtureDirectory(name)).clientFor,
  );
  return engine.run(
    'https://example.org/bundle',
    publicKey,
    store,
    trustedKeys: keys,
  );
}

List<TrustedKey> _trustedKeys(String name) {
  final decoded = jsonDecode(
    File('${fixtureDirectory(name).path}/trusted-keys.json').readAsStringSync(),
  ) as List<Object?>;
  return [
    for (final item in decoded)
      TrustedKey(
        id: (item! as Map)['id'] as String,
        publicKeyHex: (item as Map)['publicKeyHex'] as String,
        notAfter: (item as Map)['notAfter'] as String,
      ),
  ];
}

String _signedBy(String name) {
  final parsed = jsonDecode(
    File('${fixtureDirectory(name).path}/bundle.json').readAsStringSync(),
  ) as Map<String, Object?>;
  final signedBy = parsed['signedBy'];
  if (signedBy is! String || signedBy.isEmpty) {
    throw StateError('$name bundle.json has no signedBy');
  }
  return signedBy;
}

Uint8List _publicKey(String name) {
  return hexToBytes(
    File('${fixtureDirectory(name).path}/public-key.hex').readAsStringSync(),
  );
}

/// Serves one fixture directory as bundle files. Reads local bytes only.
class _FixtureHost {
  _FixtureHost(this.directory);

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
