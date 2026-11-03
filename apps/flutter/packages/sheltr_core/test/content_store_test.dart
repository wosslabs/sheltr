import 'dart:io';

import 'package:sheltr_core/bundle/format.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:test/test.dart';

void main() {
  test('installThrowsOnSecondDocumentLeavesOldContent', () {
    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        final first = _v1();
        store.install(first.manifest, first.source);
        expect(store.bundleVersion(), '2026-10-01T00:00:00Z');
        expect(_ids(store.search('sandbags')), ['doc-a-1']);

        final second = _v2();
        var loaded = 0;
        expect(
          () => store.install(second.manifest, (document) {
            loaded++;
            if (loaded == 2) {
              throw StateError('connection reset while reading ${document.id}');
            }
            return second.source(document);
          }),
          throwsA(
            isA<StateError>().having(
              (error) => error.message,
              'message',
              contains('connection reset'),
            ),
          ),
        );

        expect(loaded, 2);
        expect(store.bundleVersion(), '2026-10-01T00:00:00Z');
        expect(store.document('doc-c'), isNull);
        expect(_ids(store.search('sandbags')), ['doc-a-1']);
        expect(store.chunks('doc-a').map((chunk) => chunk.id), [
          'doc-a-0',
          'doc-a-1',
        ]);
        expectNoStaging(dir);
      } finally {
        store.close();
      }
    });
  });

  test('driverThrowMidwayLeavesOldContent', () {
    withDirectory((dir) {
      final store = ContentStore.open(
        dir,
        driver: ThrowOnDocumentDriver(const SqliteDriver(), 'doc-c'),
      );
      try {
        final first = _v1();
        store.install(first.manifest, first.source);
        expect(_ids(store.search('sandbags')), ['doc-a-1']);

        final second = _v2();
        expect(
          () => store.install(second.manifest, second.source),
          throwsA(
            isA<StateError>().having(
              (error) => error.message,
              'message',
              contains('driver stopped'),
            ),
          ),
        );

        expect(store.bundleVersion(), '2026-10-01T00:00:00Z');
        expect(store.document('doc-a')?.title, 'Flood guide');
        expect(store.document('doc-c'), isNull);
        expect(store.documents().map((document) => document.id), [
          'doc-b',
          'doc-a',
        ]);
        expect(_ids(store.search('sandbags')), ['doc-a-1']);
        expect(store.chunks('doc-a').map((chunk) => chunk.content), [
          'Move to higher ground.',
          'Place sandbags at doors.',
        ]);
        expectNoStaging(dir);
      } finally {
        store.close();
      }

      final reopened = ContentStore.open(dir);
      try {
        expect(reopened.bundleVersion(), '2026-10-01T00:00:00Z');
        expect(_ids(reopened.search('sandbags')), ['doc-a-1']);
        expect(reopened.document('doc-c'), isNull);
        expectNoStaging(dir);
      } finally {
        reopened.close();
      }
    });
  });

  test('leftoverStagingFileIsDeletedOnOpen', () {
    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        final first = _v1();
        store.install(first.manifest, first.source);
      } finally {
        store.close();
      }

      File('${dir.path}/content.db.staging').writeAsStringSync('partial');
      File('${dir.path}/content.db.staging-journal')
          .writeAsStringSync('journal');
      File('${dir.path}/content.db.staging-wal').writeAsStringSync('wal');
      File('${dir.path}/content.db.staging-shm').writeAsStringSync('shm');

      final reopened = ContentStore.open(dir);
      try {
        expectNoStaging(dir);
        expect(
          File('${dir.path}/content.db.staging-shm').existsSync(),
          isFalse,
        );
        expect(reopened.bundleVersion(), '2026-10-01T00:00:00Z');
        expect(_ids(reopened.search('sandbags')), ['doc-a-1']);
      } finally {
        reopened.close();
      }
    });
  });

  test('fts5TableCanBeCreated', () {
    final packagePubspec = File('pubspec.yaml').readAsStringSync();
    final appPubspec = File('../../pubspec.yaml').readAsStringSync();
    expect(packagePubspec, isNot(contains('source: system')));
    expect(appPubspec, isNot(contains('source: system')));
    expect(packagePubspec, contains('sqlite3:'));
    expect(appPubspec, contains('sqlite3:'));

    final db = sqlite3.openInMemory();
    try {
      final options = [
        for (final row in db.select('PRAGMA compile_options')) row.values.first,
      ];
      // compile_options strips the SQLITE_ prefix, so SQLITE_ENABLE_FTS5
      // is reported as ENABLE_FTS5.
      expect(options, contains('ENABLE_FTS5'));
      db.execute('''
        CREATE VIRTUAL TABLE chunks_fts USING fts5(
          chunk_id UNINDEXED,
          content
        )
      ''');
      db.execute(
        "INSERT INTO chunks_fts(chunk_id, content) VALUES ('c1', 'flood water')",
      );
      final rows = db.select(
        'SELECT chunk_id, bm25(chunks_fts) AS score FROM chunks_fts '
        'WHERE chunks_fts MATCH ?',
        ['"flood"'],
      );
      expect(rows, hasLength(1));
      expect(rows.single['chunk_id'], 'c1');
      expect(rows.single['score'], isA<double>());
    } finally {
      db.close();
    }

    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        final first = _v1();
        store.install(first.manifest, first.source);
        final hits = store.search('sandbags');
        expect(_ids(hits), ['doc-a-1']);
        expect(hits.single.score, isA<double>());
        expect(hits.single.chunk.pageNumber, 2);
      } finally {
        store.close();
      }
    });
  });

  test('searchBuildsTheMatchFromTokens', () {
    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        final first = _v1();
        store.install(first.manifest, first.source);

        expect(_ids(store.search('sandbags')), ['doc-a-1']);
        expect(store.search('sandbags').single.chunk.documentId, 'doc-a');
        expect(store.search('sandbags').single.chunk.pageNumber, 2);
        expect(store.search('volcano'), isEmpty);
        expect(store.search('  -- "* '), isEmpty);
        expect(_ids(store.search('sandbags NOT')), ['doc-a-1']);
        expect(_ids(store.search('sandbags AND (')), ['doc-a-1']);
        expect(() => store.search('sandbags', 0), throwsRangeError);

        final second = _v2();
        store.install(second.manifest, second.source);
        expect(store.bundleVersion(), '2026-10-05T12:00:00Z');
        expect(store.document('doc-a'), isNull);
        expect(store.document('doc-c')?.country, 'CA');
        expect(store.document('doc-c')?.hazards, ['HEATWAVE', 'WILDFIRE']);
        expect(store.document('doc-b')?.hazards, ['FLOOD']);
        expect(_ids(store.search('respirator')), ['doc-c-1']);
        expect(store.search('sandbags'), isEmpty);
      } finally {
        store.close();
      }
    });
  });

  test('chunkCountMismatchLeavesOldContent', () {
    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        final first = _v1();
        store.install(first.manifest, first.source);
        final second = _v2();
        expect(
          () => store.install(second.manifest, (document) {
            final file = second.source(document);
            if (document.id != 'doc-c') {
              return file;
            }
            return DocumentFile(
              id: file.id,
              chunks: file.chunks.sublist(0, file.chunks.length - 1),
            );
          }),
          throwsA(
            isA<StoreUpdateError>().having(
              (error) => error.message,
              'message',
              'staged 2 chunks, manifest lists 3',
            ),
          ),
        );
        expect(store.bundleVersion(), '2026-10-01T00:00:00Z');
        expect(_ids(store.search('sandbags')), ['doc-a-1']);
        expectNoStaging(dir);
      } finally {
        store.close();
      }
    });
  });

  test('installKeepsSettingsRows', () {
    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        final first = _v1();
        store.install(first.manifest, first.source);
        final side = sqlite3.open('${dir.path}/content.db');
        side.execute('INSERT INTO settings(key, value) VALUES (?, ?)', [
          'app_settings',
          '{"language":"es"}',
        ]);
        side.close();

        final second = _v2();
        store.install(second.manifest, second.source);
        expect(store.bundleVersion(), '2026-10-05T12:00:00Z');
        expect(_ids(store.search('respirator')), ['doc-c-1']);
        expect(store.search('sandbags'), isEmpty);

        final check = sqlite3.open('${dir.path}/content.db');
        final rows = check.select('SELECT value FROM settings WHERE key = ?', [
          'app_settings',
        ]);
        expect(rows.single['value'], '{"language":"es"}');
        check.close();
      } finally {
        store.close();
      }
    });
  });

  test('recordSyncRequiresInstalledContent', () {
    withDirectory((dir) {
      final store = ContentStore.open(dir);
      try {
        expect(store.lastSyncEpochSeconds(), isNull);
        expect(
          () => store.recordSync(10),
          throwsA(
            isA<StoreUpdateError>().having(
              (error) => error.message,
              'message',
              'no content installed to record a sync on',
            ),
          ),
        );

        final first = _v1();
        store.install(first.manifest, first.source);
        expect(store.lastSyncEpochSeconds(), isNull);
        store.recordSync(1700000000);
        expect(store.lastSyncEpochSeconds(), 1700000000);
        expect(store.bundleVersion(), '2026-10-01T00:00:00Z');

        final second = _v2();
        store.install(second.manifest, second.source);
        expect(store.bundleVersion(), '2026-10-05T12:00:00Z');
        expect(store.lastSyncEpochSeconds(), isNull);
      } finally {
        store.close();
      }
    });
  });

  test('opening a database from before review dates adds the columns', () {
    withDirectory((dir) {
      final db = sqlite3.open('${dir.path}/content.db');
      db.execute('''
        CREATE TABLE documents (
          id TEXT NOT NULL PRIMARY KEY,
          title TEXT NOT NULL,
          agency TEXT NOT NULL,
          published_date TEXT NOT NULL,
          licence TEXT NOT NULL,
          country TEXT,
          source_uri TEXT NOT NULL,
          source_type TEXT NOT NULL,
          checksum TEXT NOT NULL,
          file_checksum TEXT NOT NULL
        );
      ''');
      db.execute(
        'INSERT INTO documents('
        'id, title, agency, published_date, licence, country, source_uri, '
        'source_type, checksum, file_checksum'
        ') VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          'doc-a',
          'Flood guide',
          'Example Agency',
          '2024-03-15',
          'CC-BY-4.0',
          'NO',
          'https://example.org/doc-a',
          'html',
          'abc',
          'def',
        ],
      );
      db.close();

      final store = ContentStore.open(dir);
      try {
        final document = store.document('doc-a');
        expect(document?.title, 'Flood guide');
        expect(document?.reviewedOn, '');
        expect(document?.expiresOn, '');
        expect(store.search('flood'), isEmpty);
      } finally {
        store.close();
      }
    });
  });
}

void withDirectory(void Function(Directory directory) body) {
  final directory = Directory.systemTemp.createTempSync('sheltr-store-');
  try {
    body(directory);
  } finally {
    directory.deleteSync(recursive: true);
  }
}

void expectNoStaging(Directory directory) {
  expect(File('${directory.path}/content.db.staging').existsSync(), isFalse);
  expect(
    File('${directory.path}/content.db.staging-journal').existsSync(),
    isFalse,
  );
  expect(
    File('${directory.path}/content.db.staging-wal').existsSync(),
    isFalse,
  );
}

List<String> _ids(List<SearchHit> hits) => [
  for (final hit in hits) hit.chunk.id,
];

class ThrowOnDocumentDriver implements SqlDriver {
  const ThrowOnDocumentDriver(this._inner, this.documentId);

  final SqlDriver _inner;
  final String documentId;

  @override
  SqlDatabase open(String path) {
    return _ThrowOnDocumentDatabase(_inner.open(path), path, documentId);
  }
}

class _ThrowOnDocumentDatabase implements SqlDatabase {
  _ThrowOnDocumentDatabase(this._inner, this._path, this._documentId);

  final SqlDatabase _inner;
  final String _path;
  final String _documentId;

  @override
  void exec(String sql) => _inner.exec(sql);

  @override
  void run(String sql, List<Object?> parameters) {
    if (_path.endsWith('content.db.staging') &&
        sql.contains('INSERT INTO documents') &&
        parameters.isNotEmpty &&
        parameters.first == _documentId) {
      throw StateError('driver stopped while writing $_documentId');
    }
    _inner.run(sql, parameters);
  }

  @override
  List<Map<String, Object?>> all(
    String sql, [
    List<Object?> parameters = const [],
  ]) {
    return _inner.all(sql, parameters);
  }

  @override
  void close() => _inner.close();
}

class _BuiltBundle {
  const _BuiltBundle(this.manifest, this._files);

  final BundleManifest manifest;
  final Map<String, DocumentFile> _files;

  DocumentFile source(BundleDocument document) => _files[document.id]!;
}

_BuiltBundle _bundle(String version, List<_BuiltDocument> documents) {
  return _BuiltBundle(
    BundleManifest(
      formatVersion: bundleFormatVersion,
      sequence: 1,
      bundleVersion: version,
      documents: [for (final document in documents) document.entry],
    ),
    {for (final document in documents) document.entry.id: document.file},
  );
}

_BuiltBundle _v1() {
  return _bundle('2026-10-01T00:00:00Z', [
    _doc('doc-a', 'Flood guide', 'NO', [
      ('Move to higher ground.', 1),
      ('Place sandbags at doors.', 2),
    ]),
    _doc('doc-b', 'Evacuation', null, [('Follow the evacuation route.', null)]),
  ]);
}

_BuiltBundle _v2() {
  return _bundle('2026-10-05T12:00:00Z', [
    _doc('doc-b', 'Evacuation', null, [('Follow the evacuation route.', null)]),
    _doc(
      'doc-c',
      'Wildfire smoke',
      'CA',
      [('Stay indoors.', 1), ('Wear a respirator outside.', 1)],
      ['WILDFIRE', 'HEATWAVE', 'WILDFIRE'],
    ),
  ]);
}

class _BuiltDocument {
  const _BuiltDocument(this.entry, this.file);

  final BundleDocument entry;
  final DocumentFile file;
}

_BuiltDocument _doc(
  String id,
  String title,
  String? country,
  List<(String, int?)> chunks, [
  List<String> hazards = const ['FLOOD'],
]) {
  final fileChunks = [
    for (var i = 0; i < chunks.length; i++)
      DocumentChunk(
        id: '$id-$i',
        content: chunks[i].$1,
        pageNumber: chunks[i].$2,
      ),
  ];
  return _BuiltDocument(
    BundleDocument(
      id: id,
      title: title,
      agency: 'Example Agency',
      publishedDate: '2024-03-15',
      reviewedOn: '2026-11-02',
      expiresOn: '2027-11-02',
      licence: 'CC-BY-4.0',
      country: country,
      hazards: hazards,
      sourceUri: 'https://example.org/$id',
      sourceType: 'pdf',
      checksum: '0' * 64,
      fileChecksum: '1' * 64,
      chunks: [
        for (final chunk in fileChunks)
          BundleChunkRef(id: chunk.id, checksum: '2' * 64),
      ],
    ),
    DocumentFile(id: id, chunks: fileChunks),
  );
}
