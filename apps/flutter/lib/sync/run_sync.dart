import 'dart:typed_data';

import 'package:sheltr_core/sheltr_core.dart';

import 'https_fetch.dart';

/// True when this build has an https bundle host and one or two publisher keys.
///
/// An empty list or a blank URL does not open a socket.
bool publishedBundleConfigured(String baseUrl, List<TrustedKey> keys) {
  if (keys.isEmpty) return false;
  try {
    requireHttps(baseUrl);
    compileTrustedKeys(keys);
  } on Object {
    return false;
  }
  return true;
}

/// Syncs [store] from the compiled host. Returns null when this build has no
/// host or no keys, and does not open a socket in that case.
Future<SyncResult?> runPublishedSync({
  required String baseUrl,
  required List<TrustedKey> keys,
  required ContentStore? store,
}) async {
  if (store == null || !publishedBundleConfigured(baseUrl, keys)) {
    return null;
  }
  final engine = SyncEngine(
    clientFor: (url) => FetchSyncClient(
      url,
      fetch: httpsSyncFetch,
      streamFetch: httpsSyncStream,
    ),
  );
  return engine.run(baseUrl, _publicKey(keys.first), store, trustedKeys: keys);
}

Uint8List _publicKey(TrustedKey key) {
  final hex = key.publicKeyHex;
  final out = Uint8List(hex.length ~/ 2);
  for (var i = 0; i < out.length; i++) {
    out[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
  }
  return out;
}
