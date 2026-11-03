import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:sheltr_core/sheltr_core.dart';
import 'package:sqlite3/common.dart';
import 'package:sqlite3/wasm.dart';

import 'memory_store_files.dart';

/// When true, the sample bundle is installed with an expiry in the past.
const webStaleSample = bool.fromEnvironment('SHELTR_WEB_STALE');

/// Opens an in-memory store and installs the sample bundle for UI testing.
///
/// This target is for the browser. Android and iOS open the device database
/// and do not install the sample here.
Future<ContentStore> openAppStore() async {
  final sqlite = await WasmSqlite3.loadFromUrlString('sqlite3.wasm');
  final filesystem = InMemoryFileSystem();
  sqlite.registerVirtualFileSystem(filesystem, makeDefault: true);
  final store = ContentStore.open(
    '/sheltr',
    files: MemoryStoreFiles(filesystem.fileData),
    driver: _WasmSqlDriver(sqlite),
  );
  await _installSample(store);
  return store;
}

class _WasmSqlDriver implements SqlDriver {
  _WasmSqlDriver(this._sqlite);

  final WasmSqlite3 _sqlite;

  @override
  SqlDatabase open(String path) => BundledSqlDatabase(_sqlite.open(path));
}

Future<void> _installSample(ContentStore store) async {
  final parsed = parseBundleManifest(
    await rootBundle.loadString('assets/sample-bundle/bundle.json'),
  );
  final bytes = <String, ByteData>{};
  for (final document in parsed.documents) {
    bytes[document.id] = await rootBundle.load(
      'assets/sample-bundle/documents/${document.id}.json',
    );
  }
  final manifest = webStaleSample ? _expired(parsed) : parsed;
  store.install(manifest, (document) {
    final data = bytes[document.id]!;
    return parseDocumentFileBytes(
      data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
    );
  });
}

BundleManifest _expired(BundleManifest parsed) {
  return BundleManifest(
    formatVersion: parsed.formatVersion,
    sequence: parsed.sequence,
    bundleVersion: parsed.bundleVersion,
    documents: [
      for (final document in parsed.documents)
        BundleDocument(
          id: document.id,
          title: document.title,
          agency: document.agency,
          publishedDate: document.publishedDate,
          reviewedOn: document.reviewedOn,
          expiresOn: '2020-01-01',
          licence: document.licence,
          country: document.country,
          hazards: document.hazards,
          sourceUri: document.sourceUri,
          sourceType: document.sourceType,
          checksum: document.checksum,
          fileChecksum: document.fileChecksum,
          chunks: document.chunks,
        ),
    ],
    cards: parsed.cards,
    model: parsed.model,
  );
}
