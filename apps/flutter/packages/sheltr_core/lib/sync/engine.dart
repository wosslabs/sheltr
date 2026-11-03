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
/// 6. When the manifest names a model, pass that response stream to a staging
///    file with a running SHA-256. The body is not held as one buffer. Writing
///    stops, and the stream is cancelled, if the next chunk would pass the
///    signed byteSize. The length and the hash are checked, then the staging
///    file is renamed into place. A mismatch deletes the staging file. A
///    model failure keeps the content result and leaves any previous verified
///    model file in place. An up-to-date sync retries the download when the
///    signed sha256 or byteSize differs from the installed model.
///
/// Document downloads finish and are verified before the store is changed, so
/// a failure at those steps returns [SyncFailed] with the old content active.
/// A model failure is the exception: the content result is already decided.
/// The manifest, the signature, and document files are held in memory. The
/// model body stays a stream of chunks until each one is written. The engine
/// does not schedule or wait.
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
        final result = SyncUpToDate(bundleVersion: manifest.bundleVersion);
        await _installOptionalModel(
          client,
          manifest,
          store,
          onlyWhenModelDiffers: true,
        );
        return result;
      }
      throw BundleFormatError(
        'Refusing bundle sequence ${manifest.sequence}; it is older than or equal to the installed sequence $installedSequence.',
      );
    }
    if (manifest.bundleVersion == installedVersion) {
      store.recordSync(_nowEpochSeconds());
      final result = SyncUpToDate(bundleVersion: manifest.bundleVersion);
      await _installOptionalModel(
        client,
        manifest,
        store,
        onlyWhenModelDiffers: true,
      );
      return result;
    }

    final downloaded = await _downloadChanged(client, manifest, store);
    store.install(
      manifest,
      (document) => _documentFor(document, downloaded, store),
    );
    store.recordSync(_nowEpochSeconds());
    final result = SyncUpdated(
      bundleVersion: manifest.bundleVersion,
      documentsDownloaded: downloaded.length,
    );
    await _installOptionalModel(
      client,
      manifest,
      store,
      onlyWhenModelDiffers: false,
    );
    return result;
  }
}

/// Downloads [manifest.model] after the content result is decided.
///
/// Failures are swallowed so that result stays unchanged. The comparison with
/// the installed model runs inside that same guard, so a throw there cannot
/// replace an up-to-date content result. When [onlyWhenModelDiffers] is set,
/// a file whose sha256 and byteSize already match the signed model is left in
/// place. An up-to-date sync uses that so a download that failed during
/// install is retried only while the signed file still differs.
Future<void> _installOptionalModel(
  SyncClient client,
  BundleManifest manifest,
  ContentStore store, {
  required bool onlyWhenModelDiffers,
}) async {
  final model = manifest.model;
  if (model == null) {
    return;
  }
  try {
    if (onlyWhenModelDiffers && !_signedModelDiffers(store, model)) {
      return;
    }
    await store.commitModelStream(client.fetchBundleFile(model.file), model);
  } on Object {
    // The content result is already decided. Keep it, and keep the previous
    // verified model file.
  }
}

bool _signedModelDiffers(ContentStore store, BundleModel model) {
  final installed = store.installedModel();
  if (installed == null) {
    return true;
  }
  return installed.sha256 != model.sha256 ||
      installed.byteSize != model.byteSize;
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
