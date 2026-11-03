import 'dart:async';
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

  /// A bundle-relative file, such as the optional model.
  ///
  /// The body arrives in chunks. The caller writes those chunks onward and
  /// does not assemble them into one list. [relativePath] is appended to the
  /// base URL. A path that could leave that directory is refused.
  Stream<List<int>> fetchBundleFile(String relativePath);
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
final RegExp _bundleSegment = RegExp(r'^[A-Za-z0-9._-]+$');

/// True when [relativePath] stays inside the bundle directory.
bool _safeBundlePath(String relativePath) {
  if (relativePath.isEmpty ||
      relativePath.startsWith('/') ||
      relativePath.contains('\\')) {
    return false;
  }
  final parts = relativePath.split('/');
  for (final part in parts) {
    if (part.isEmpty ||
        part == '.' ||
        part == '..' ||
        !_bundleSegment.hasMatch(part)) {
      return false;
    }
  }
  return true;
}

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

  @override
  Stream<List<int>> fetchBundleFile(String relativePath) async* {
    if (!_safeBundlePath(relativePath)) {
      throw SyncHttpError('refusing bundle path: $relativePath');
    }
    final response = await _fetch('$_base/$relativePath');
    if (!response.ok) {
      throw SyncHttpError('GET $relativePath returned ${response.status}');
    }
    final bytes = response.bytes;
    const chunkSize = 65536;
    for (var offset = 0; offset < bytes.length; offset += chunkSize) {
      final end = offset + chunkSize < bytes.length
          ? offset + chunkSize
          : bytes.length;
      yield bytes.sublist(offset, end);
    }
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
