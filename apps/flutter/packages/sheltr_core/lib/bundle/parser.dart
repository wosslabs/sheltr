import 'dart:convert';
import 'dart:typed_data';

import 'format.dart';
import 'sha256.dart';

/// A bundle file that does not follow the format.
class BundleFormatError implements Exception {
  BundleFormatError(this.message);

  final String message;

  @override
  String toString() => 'BundleFormatError: $message';
}

/// Parses bundle.json. Rejects any formatVersion other than
/// [bundleFormatVersion] before the rest of the file is read, and rejects
/// blank ids.
BundleManifest parseBundleManifest(String text) {
  final root = _parseObject(text, 'bundle.json');
  if (root['formatVersion'] != bundleFormatVersion) {
    final raw = root['formatVersion'];
    final shown = raw == null ? '(missing)' : '$raw';
    throw BundleFormatError(
      'Unsupported bundle formatVersion $shown; this app reads formatVersion $bundleFormatVersion only.',
    );
  }
  final documents = _array(root['documents'], 'bundle.json documents');
  final parsed = <BundleDocument>[];
  for (var i = 0; i < documents.length; i++) {
    parsed.add(_parseBundleDocument(_object(documents[i], 'documents[$i]')));
  }
  return BundleManifest(
    formatVersion: bundleFormatVersion,
    bundleVersion: _string(root['bundleVersion'], 'bundleVersion'),
    documents: List<BundleDocument>.unmodifiable(parsed),
  );
}

BundleDocument _parseBundleDocument(Map<String, dynamic> raw) {
  final id = _notBlank(_string(raw['id'], 'document id'), 'document id');
  final chunks = _array(raw['chunks'], 'document $id chunks');
  final parsedChunks = <BundleChunkRef>[];
  for (final rawChunk in chunks) {
    final chunk = _object(rawChunk, 'chunk of document $id');
    parsedChunks.add(
      BundleChunkRef(
        id: _notBlank(
          _string(chunk['id'], 'chunk id'),
          'chunk id in document $id',
        ),
        checksum: _string(chunk['checksum'], 'chunk checksum'),
      ),
    );
  }
  return BundleDocument(
    id: id,
    title: _string(raw['title'], 'title'),
    agency: _string(raw['agency'], 'agency'),
    publishedDate: _string(raw['publishedDate'], 'publishedDate'),
    licence: _string(raw['licence'], 'licence'),
    country: _nullableString(raw['country'], 'country'),
    hazards: List<String>.unmodifiable(
      _array(
        raw['hazards'],
        'hazards',
      ).map((hazard) => _string(hazard, 'hazard')),
    ),
    sourceUri: _string(raw['sourceUri'], 'sourceUri'),
    sourceType: _string(raw['sourceType'], 'sourceType'),
    checksum: _string(raw['checksum'], 'checksum'),
    fileChecksum: _string(raw['fileChecksum'], 'fileChecksum'),
    chunks: List<BundleChunkRef>.unmodifiable(parsedChunks),
  );
}

/// Parses `documents/<id>.json` from its text.
DocumentFile parseDocumentFile(String text) {
  final root = _parseObject(text, 'document file');
  final id = _notBlank(
    _string(root['id'], 'document file id'),
    'document file id',
  );
  final chunks = _array(root['chunks'], 'document file chunks');
  final parsed = <DocumentChunk>[];
  for (final rawChunk in chunks) {
    final chunk = _object(rawChunk, 'chunk of document $id');
    final pageNumber = chunk['pageNumber'];
    if (pageNumber != null && pageNumber is! int) {
      throw BundleFormatError(
        'pageNumber of a chunk in document $id must be an integer or null.',
      );
    }
    parsed.add(
      DocumentChunk(
        id: _notBlank(
          _string(chunk['id'], 'chunk id'),
          'chunk id in document $id',
        ),
        content: _string(chunk['content'], 'chunk content'),
        pageNumber: pageNumber as int?,
      ),
    );
  }
  return DocumentFile(id: id, chunks: List<DocumentChunk>.unmodifiable(parsed));
}

/// Parses `documents/<id>.json` from the exact bytes read (UTF-8).
DocumentFile parseDocumentFileBytes(Uint8List bytes) {
  return parseDocumentFile(utf8.decode(bytes));
}

/// SHA-256 (lower-case hex) of the exact bytes of `documents/<id>.json`.
///
/// Computed over the bytes read, never over a re-encoded string.
String documentFileChecksum(Uint8List bytes) {
  return sha256Hex(bytes);
}

/// Throws [BundleFormatError] unless [bytes] are the bytes [doc] describes.
void verifyDocumentFile(BundleDocument doc, Uint8List bytes) {
  final actual = documentFileChecksum(bytes);
  if (actual != doc.fileChecksum) {
    throw BundleFormatError(
      'Document file ${doc.id} does not match the manifest: expected fileChecksum ${doc.fileChecksum}, got $actual.',
    );
  }
}

Map<String, dynamic> _parseObject(String text, String what) {
  final Object? value;
  try {
    value = jsonDecode(text);
  } catch (error) {
    throw BundleFormatError('$what is not valid JSON: $error');
  }
  if (value is! Map) {
    throw BundleFormatError('$what must be a JSON object');
  }
  return Map<String, dynamic>.from(value);
}

Map<String, dynamic> _object(Object? value, String what) {
  if (value is! Map) {
    throw BundleFormatError('$what must be an object.');
  }
  return Map<String, dynamic>.from(value);
}

List<dynamic> _array(Object? value, String what) {
  if (value is! List) {
    throw BundleFormatError('$what must be an array.');
  }
  return value;
}

String _string(Object? value, String what) {
  if (value is! String) {
    throw BundleFormatError('$what must be a string.');
  }
  return value;
}

String? _nullableString(Object? value, String what) {
  if (value == null) {
    return null;
  }
  return _string(value, what);
}

String _notBlank(String value, String what) {
  if (value.trim().isEmpty) {
    throw BundleFormatError('A $what must not be blank.');
  }
  return value;
}
