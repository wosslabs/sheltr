import 'dart:typed_data';

import '../bundle/format.dart';

/// Fetches the files of one published bundle.
///
/// Every call returns the exact bytes served, or throws.
abstract interface class SyncClient {
  /// bundle.json.
  Future<Uint8List> fetchManifestBytes();

  /// bundle.json.sig (raw 64-byte Ed25519 signature).
  Future<Uint8List> fetchSignatureBytes();

  /// `documents/<id>.json`.
  Future<Uint8List> fetchDocumentFile(String id);
}

/// Thrown for a base URL that is not https, or a response that is not a success.
class SyncHttpError implements Exception {
  SyncHttpError(this.message);

  final String message;

  @override
  String toString() => message;
}

/// One HTTP response, reduced to the fields the sync client reads.
class SyncResponse {
  SyncResponse({required this.ok, required this.status, required this.bytes});

  final bool ok;
  final int status;
  final Uint8List bytes;
}

/// Supplies response bytes for one URL. Callers pass this in; the default
/// refuses the request so this package never opens a socket.
typedef SyncFetch = Future<SyncResponse> Function(String url);

final RegExp _documentId = RegExp(r'^[\p{L}\p{N}_-]+$', unicode: true);

/// [SyncClient] over an injected fetch.
///
/// [baseUrl] is the bundle directory, for example
/// `https://example.org/bundle`. It must be https. Plain http is refused, so
/// content cannot be read or replaced in transit before the signature check.
class FetchSyncClient implements SyncClient {
  FetchSyncClient(String baseUrl, {SyncFetch? fetch})
    : _base = requireHttps(baseUrl).replaceAll(RegExp(r'/+$'), ''),
      _fetch = fetch ?? _refuseSocket;

  final String _base;
  final SyncFetch _fetch;

  @override
  Future<Uint8List> fetchManifestBytes() {
    return _fetchBytes(bundleManifestFile);
  }

  @override
  Future<Uint8List> fetchSignatureBytes() {
    return _fetchBytes(bundleSignatureFile);
  }

  @override
  Future<Uint8List> fetchDocumentFile(String id) {
    // Ids are hex from the publisher; refuse anything that could change the path.
    if (!_documentId.hasMatch(id)) {
      return Future<Uint8List>.error(
        SyncHttpError('refusing document id with unexpected characters: $id'),
      );
    }
    return _fetchBytes('$bundleDocumentsDir/$id.json');
  }

  Future<Uint8List> _fetchBytes(String path) async {
    final response = await _fetch('$_base/$path');
    if (!response.ok) {
      throw SyncHttpError('GET $path returned ${response.status}');
    }
    return response.bytes;
  }
}

/// Returns [baseUrl] if it is an https URL; throws [SyncHttpError] otherwise.
String requireHttps(String baseUrl) {
  if (!RegExp(r'^https://', caseSensitive: false).hasMatch(baseUrl) ||
      baseUrl.length <= 'https://'.length) {
    throw SyncHttpError('bundle base URL must use https: $baseUrl');
  }
  return baseUrl;
}

Future<SyncResponse> _refuseSocket(String url) {
  return Future<SyncResponse>.error(
    SyncHttpError('bundle fetch is not configured for $url'),
  );
}
