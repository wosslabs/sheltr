import 'dart:convert';
import 'dart:typed_data';

import '../bundle/format.dart';
import '../bundle/parser.dart';
import '../signing/trusted_keys.dart';
import '../signing/verifier.dart';
import '../store/content_store.dart';
import 'client.dart';
import 'result.dart';

/// One sync of the on-device content with a published bundle.
///
/// 1. Fetch bundle.json and bundle.json.sig.
/// 2. Verify the Ed25519 signature over the exact manifest bytes. A bad
///    signature ends the sync before the manifest is parsed or the store is
///    touched.
/// 3. Parse the manifest (formatVersion 2 only). A sequence that does not
///    exceed the installed one fails. The same sequence and bundleVersion
///    means up to date.
/// 4. Download only the documents whose fileChecksum differs from the stored
///    one, and check each download's SHA-256 against the signed manifest.
/// 5. Install through [ContentStore.install], which replaces the content
///    atomically or leaves it unchanged.
///
/// Every download finishes and is verified before the store is changed, so a
/// failure at any step returns [SyncFailed] with the old content active.
/// Downloaded files are held in memory until the install. The engine does not
/// schedule, wait, or retry.
class SyncEngine {
  SyncEngine({
    SyncClient Function(String baseUrl)? clientFor,
    int Function()? nowEpochSeconds,
  }) : _clientFor = clientFor ?? ((baseUrl) => FetchSyncClient(baseUrl)),
       _nowEpochSeconds =
           nowEpochSeconds ??
           (() => DateTime.now().millisecondsSinceEpoch ~/ 1000);

  final SyncClient Function(String baseUrl) _clientFor;
  final int Function() _nowEpochSeconds;

  /// Fetches and installs [baseUrl] when its signature verifies.
  ///
  /// A base URL that does not start with `https://` is refused before any
  /// request. [verifySignature] is awaited and does not throw; false becomes
  /// [SyncRejectedSignature] and the manifest bytes are not parsed.
  ///
  /// When [trustedKeys] is set, `signedBy` must name one of those keys and the
  /// signature must verify under it. An unknown id is [SyncRejectedSignature].
  /// A listed key past `notAfter` still verifies. With no list, [publicKey]
  /// is used, including for bundles that have no `signedBy`.
  Future<SyncResult> run(
    String baseUrl,
    Uint8List publicKey,
    ContentStore store, {
    List<TrustedKey>? trustedKeys,
  }) async {
    try {
      requireHttps(baseUrl);
      return await _sync(_clientFor(baseUrl), publicKey, store, trustedKeys);
    } on Object catch (error) {
      return SyncFailed(reason: _failureReason(error));
    }
  }

  Future<SyncResult> _sync(
    SyncClient client,
    Uint8List publicKey,
    ContentStore store,
    List<TrustedKey>? trustedKeys,
  ) async {
    final manifestBytes = await client.fetchManifestBytes();
    final signature = await client.fetchSignatureBytes();
    final key = _keyFor(
      manifestBytes,
      publicKey,
      trustedKeys,
      _nowEpochSeconds(),
    );
    if (key == null) {
      return const SyncRejectedSignature();
    }
    final verified = await verifySignature(key, manifestBytes, signature);
    if (!verified) {
      return const SyncRejectedSignature();
    }

    final manifest = parseBundleManifest(utf8.decode(manifestBytes));
    final installedSequence = store.sequence();
    final installedVersion = store.bundleVersion();
    if (installedSequence != null && manifest.sequence <= installedSequence) {
      if (manifest.sequence == installedSequence &&
          manifest.bundleVersion == installedVersion) {
        store.recordSync(_nowEpochSeconds());
        return SyncUpToDate(bundleVersion: manifest.bundleVersion);
      }
      throw BundleFormatError(
        'Refusing bundle sequence ${manifest.sequence}; it is older than or equal to the installed sequence $installedSequence.',
      );
    }
    if (manifest.bundleVersion == installedVersion) {
      store.recordSync(_nowEpochSeconds());
      return SyncUpToDate(bundleVersion: manifest.bundleVersion);
    }

    final downloaded = await _downloadChanged(client, manifest, store);
    store.install(
      manifest,
      (document) => _documentFor(document, downloaded, store),
    );
    store.recordSync(_nowEpochSeconds());
    return SyncUpdated(
      bundleVersion: manifest.bundleVersion,
      documentsDownloaded: downloaded.length,
    );
  }
}

/// The single public key when no list is compiled in. Otherwise the public key
/// named by `signedBy`. Null when that id is not on the list. A past notAfter
/// does not reject.
Uint8List? _keyFor(
  Uint8List manifestBytes,
  Uint8List publicKey,
  List<TrustedKey>? trustedKeys,
  int nowEpochSeconds,
) {
  if (trustedKeys == null) return publicKey;
  final today = utcDateText(
    DateTime.fromMillisecondsSinceEpoch(nowEpochSeconds * 1000, isUtc: true),
  );
  final decision = decideTrustedKey(
    readSignedBy(manifestBytes),
    trustedKeys,
    today,
  );
  if (decision is! TrustedKeyAccepted) return null;
  return decision.publicKey;
}

/// Fetches and verifies every document whose file differs from the stored one.
/// Throws on the first failure.
Future<Map<String, DocumentFile>> _downloadChanged(
  SyncClient client,
  BundleManifest manifest,
  ContentStore store,
) async {
  final downloaded = <String, DocumentFile>{};
  for (final document in manifest.documents) {
    if (store.document(document.id)?.fileChecksum == document.fileChecksum) {
      continue;
    }
    final bytes = await client.fetchDocumentFile(document.id);
    verifyDocumentFile(document, bytes);
    downloaded[document.id] = parseDocumentFileBytes(bytes);
  }
  return downloaded;
}

/// Downloaded files for changed documents; the stored chunks for unchanged ones.
///
/// An unchanged document has the same fileChecksum as the stored one, so its
/// stored chunks are the content of that exact file.
DocumentFile _documentFor(
  BundleDocument document,
  Map<String, DocumentFile> downloaded,
  ContentStore store,
) {
  final fresh = downloaded[document.id];
  if (fresh != null) {
    return fresh;
  }
  final chunks = store.chunks(document.id);
  return DocumentFile(
    id: document.id,
    chunks: [
      for (final chunk in chunks)
        DocumentChunk(
          id: chunk.id,
          content: chunk.content,
          pageNumber: chunk.pageNumber,
        ),
    ],
  );
}

String _failureReason(Object error) {
  final message = switch (error) {
    SyncHttpError(:final message) => message,
    BundleFormatError(:final message) => message,
    StoreUpdateError(:final message) => message,
    FormatException(:final message) => message,
    _ => error.toString(),
  };
  return message.isEmpty ? 'sync failed' : message;
}
