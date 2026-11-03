import 'dart:io';

import 'package:sqlite3/sqlite3.dart';

import '../bundle/format.dart';

/// A content update that did not become the active database.
class StoreUpdateError implements Exception {
  StoreUpdateError(this.message);

  final String message;

  @override
  String toString() => message;
}

class StoredDocument {
  const StoredDocument({
    required this.id,
    required this.title,
    required this.agency,
    required this.publishedDate,
    required this.reviewedOn,
    required this.expiresOn,
    required this.licence,
    required this.country,
    required this.hazards,
    required this.sourceUri,
    required this.sourceType,
    required this.checksum,
    required this.fileChecksum,
  });

  final String id;
  final String title;
  final String agency;
  final String publishedDate;

  /// ISO date, YYYY-MM-DD.
  final String reviewedOn;

  /// ISO date, YYYY-MM-DD. Past this date the document is stale.
  final String expiresOn;
  final String licence;
  final String? country;
  final List<String> hazards;
  final String sourceUri;
  final String sourceType;
  final String checksum;
  final String fileChecksum;
}

class StoredChunk {
  const StoredChunk({
    required this.id,
    required this.documentId,
    required this.content,
    required this.pageNumber,
    required this.position,
  });

  final String id;
  final String documentId;
  final String content;
  final int? pageNumber;
  final int position;
}

class SearchHit {
  const SearchHit({required this.chunk, required this.score});

  final StoredChunk chunk;

  /// FTS5 bm25 score. Lower is a better match.
  final double score;
}

/// Opens the databases [ContentStore] reads and stages.
///
/// Tests pass a driver that throws from a later statement. The store then
/// leaves `content.db` unchanged and removes the staging file.
abstract interface class SqlDriver {
  SqlDatabase open(String path);
}

/// Statements the store runs. Parameterised writes go through [run], so a
/// driver can fail one insert without the caller's text reaching SQLite.
abstract interface class SqlDatabase {
  void exec(String sql);

  void run(String sql, List<Object?> parameters);

  List<Map<String, Object?>> all(
    String sql, [
    List<Object?> parameters = const [],
  ]);

  void close();
}

/// Opens databases with the bundled sqlite3 library (FTS5 enabled).
class SqliteDriver implements SqlDriver {
  const SqliteDriver();

  @override
  SqlDatabase open(String path) => _SqliteDatabase(sqlite3.open(path));
}

/// One complete bundle in `content.db`.
///
/// [install] writes the new bundle into `content.db.staging`, checks its row
/// counts, closes that file, and renames it over `content.db`. The active
/// file is not modified until that rename. Any throw before the rename deletes
/// the staging file and leaves the previous bundle in place.
class ContentStore {
  ContentStore._(this._directory, this._driver, this._db);

  /// Opens [directory]/`content.db`, creating the schema if needed.
  ///
  /// A leftover `content.db.staging` from an interrupted install is deleted
  /// first, along with its `-journal`, `-wal`, and `-shm` sidecars.
  factory ContentStore.open(Directory directory, {SqlDriver? driver}) {
    final resolved = driver ?? const SqliteDriver();
    _deleteStaging(directory);
    final db = resolved.open(_path(directory, _contentFile));
    try {
      db.exec(_schemaSql);
      _ensureReviewColumns(db);
    } catch (error) {
      db.close();
      rethrow;
    }
    return ContentStore._(directory, resolved, db);
  }

  final Directory _directory;
  final SqlDriver _driver;
  SqlDatabase _db;
  var _closed = false;

  String get _contentPath => _path(_directory, _contentFile);

  String get _stagingPath => _path(_directory, _stagingFile);

  void close() {
    if (_closed) {
      return;
    }
    _closed = true;
    _db.close();
  }

  String? bundleVersion() {
    final rows = _db.all(
      'SELECT bundle_version FROM schema_version WHERE id = 1',
    );
    if (rows.isEmpty) {
      return null;
    }
    return rows.first['bundle_version'] as String;
  }

  /// Publication sequence of the installed bundle, or null when nothing is installed.
  int? sequence() {
    final rows = _db.all('SELECT sequence FROM schema_version WHERE id = 1');
    if (rows.isEmpty) {
      return null;
    }
    return rows.first['sequence'] as int;
  }

  StoredDocument? document(String id) {
    final rows = _db.all('SELECT * FROM documents WHERE id = ?', [id]);
    if (rows.isEmpty) {
      return null;
    }
    final hazards = _db.all(
      'SELECT hazard FROM document_hazards WHERE document_id = ? ORDER BY hazard',
      [id],
    );
    return _document(rows.first, [
      for (final row in hazards) row['hazard'] as String,
    ]);
  }

  List<StoredDocument> documents() {
    final rows = _db.all('SELECT * FROM documents ORDER BY title, id');
    final hazardRows = _db.all(
      'SELECT document_id, hazard FROM document_hazards ORDER BY document_id, hazard',
    );
    final byDocument = <String, List<String>>{};
    for (final row in hazardRows) {
      final id = row['document_id'] as String;
      byDocument.putIfAbsent(id, () => []).add(row['hazard'] as String);
    }
    return [
      for (final row in rows)
        _document(row, byDocument[row['id'] as String] ?? const []),
    ];
  }

  List<StoredChunk> chunks(String documentId) {
    final rows = _db.all(
      'SELECT * FROM chunks WHERE document_id = ? ORDER BY position',
      [documentId],
    );
    return [for (final row in rows) _chunk(row)];
  }

  /// Keyword search. [query] is split into words and each word is quoted, so
  /// FTS5 operators in the user's text are never executed. An empty word list
  /// returns no rows. [limit] must be positive.
  List<SearchHit> search(String query, [int limit = 20]) {
    if (limit <= 0) {
      throw RangeError('limit must be positive');
    }
    final match = _ftsQueryFor(query);
    if (match == null) {
      return const [];
    }
    final rows = _db.all(
      '''
      SELECT chunks.id, chunks.document_id, chunks.content, chunks.page_number,
             chunks.position, bm25(chunks_fts) AS score
      FROM chunks_fts
      JOIN chunks ON chunks.id = chunks_fts.chunk_id
      WHERE chunks_fts MATCH ?
      ORDER BY score
      LIMIT ?
      ''',
      [match, limit],
    );
    return [
      for (final row in rows)
        SearchHit(chunk: _chunk(row), score: (row['score'] as num).toDouble()),
    ];
  }

  int? lastSyncEpochSeconds() {
    final rows = _db.all(
      'SELECT last_sync_epoch_seconds FROM schema_version WHERE id = 1',
    );
    if (rows.isEmpty) {
      return null;
    }
    return rows.first['last_sync_epoch_seconds'] as int?;
  }

  /// Records a successful sync. Throws [StoreUpdateError] if nothing is installed.
  void recordSync(int epochSeconds) {
    if (bundleVersion() == null) {
      throw StoreUpdateError('no content installed to record a sync on');
    }
    _db.run(
      'UPDATE schema_version SET last_sync_epoch_seconds = ? WHERE id = 1',
      [epochSeconds],
    );
  }

  /// Replaces the active bundle with [manifest].
  ///
  /// [documents] supplies each document file. It runs before the staging
  /// database is created, so a source that throws does not leave a staging
  /// file behind. Row counts are checked before the staging file is renamed
  /// over `content.db`.
  void install(
    BundleManifest manifest,
    DocumentFile Function(BundleDocument document) documents,
  ) {
    if (manifest.formatVersion != bundleFormatVersion) {
      throw StoreUpdateError(
        'unsupported bundle format ${manifest.formatVersion}',
      );
    }
    final files = <String, DocumentFile>{};
    for (final document in manifest.documents) {
      final file = documents(document);
      if (file.id != document.id) {
        throw StoreUpdateError(
          'document file ${file.id} does not match manifest entry ${document.id}',
        );
      }
      files[document.id] = file;
    }

    // Settings live in the same file and are not part of the bundle. Copy
    // them into the staging database or the rename would drop them.
    final settings = _db.all('SELECT key, value FROM settings ORDER BY key');
    _deleteStaging(_directory);

    SqlDatabase? staging;
    var renamed = false;
    var activeClosed = false;
    try {
      staging = _driver.open(_stagingPath);
      staging.exec(_schemaSql);
      staging.exec('BEGIN');
      try {
        _clearContent(staging);
        _insertContent(staging, manifest, files);
        _copySettings(staging, settings);
        _verifyContent(staging, manifest);
        staging.exec('COMMIT');
      } catch (_) {
        try {
          staging.exec('ROLLBACK');
        } on Object {
          // Keep the original failure. The staging file is deleted below.
        }
        rethrow;
      }
      staging.close();
      staging = null;
      _db.close();
      activeClosed = true;
      File(_stagingPath).renameSync(_contentPath);
      renamed = true;
      _db = _openContent();
      activeClosed = false;
      // The rename moves only the database file. Drop a journal or wal that
      // SQLite left beside the staging name.
      _deleteStaging(_directory);
    } catch (_) {
      _closeQuietly(staging);
      if (!renamed) {
        _deleteStaging(_directory);
      }
      if (activeClosed) {
        try {
          _db = _openContent();
        } on Object {
          // Report the install failure. A second failure while reopening the
          // old file would hide it.
        }
      }
      rethrow;
    }
  }

  SqlDatabase _openContent() {
    final db = _driver.open(_contentPath);
    db.exec(_schemaSql);
    _ensureReviewColumns(db);
    return db;
  }
}

class _SqliteDatabase implements SqlDatabase {
  _SqliteDatabase(this._db);

  final Database _db;
  var _closed = false;

  @override
  void close() {
    if (_closed) {
      return;
    }
    _closed = true;
    _db.close();
  }

  @override
  void exec(String sql) => _db.execute(sql);

  @override
  void run(String sql, List<Object?> parameters) =>
      _db.execute(sql, parameters);

  @override
  List<Map<String, Object?>> all(
    String sql, [
    List<Object?> parameters = const [],
  ]) {
    final rows = _db.select(sql, parameters);
    return [
      for (final row in rows)
        {for (final column in row.keys) column: row[column]},
    ];
  }
}

const _contentFile = 'content.db';
const _stagingFile = 'content.db.staging';

const _stagingFiles = [
  _stagingFile,
  '$_stagingFile-journal',
  '$_stagingFile-wal',
  '$_stagingFile-shm',
];

String _path(Directory directory, String name) => '${directory.path}/$name';

void _deleteStaging(Directory directory) {
  for (final name in _stagingFiles) {
    final file = File(_path(directory, name));
    if (file.existsSync()) {
      file.deleteSync();
    }
  }
}

/// Databases created before review dates existed gain the columns in place.
/// New databases already have them from [_schemaSql].
void _ensureReviewColumns(SqlDatabase database) {
  final names = {
    for (final row in database.all('PRAGMA table_info(documents)'))
      row['name'] as String,
  };
  if (!names.contains('reviewed_on')) {
    database.exec(
      "ALTER TABLE documents ADD COLUMN reviewed_on TEXT NOT NULL DEFAULT ''",
    );
  }
  if (!names.contains('expires_on')) {
    database.exec(
      "ALTER TABLE documents ADD COLUMN expires_on TEXT NOT NULL DEFAULT ''",
    );
  }
}

void _closeQuietly(SqlDatabase? database) {
  if (database == null) {
    return;
  }
  try {
    database.close();
  } on Object {
    // The statement that failed may already have closed it.
  }
}

/// The on-device schema. Content tables hold exactly one bundle. `settings`
/// is not cleared by an install.
const _schemaSql = '''
CREATE TABLE IF NOT EXISTS documents (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  agency TEXT NOT NULL,
  published_date TEXT NOT NULL,
  reviewed_on TEXT NOT NULL,
  expires_on TEXT NOT NULL,
  licence TEXT NOT NULL,
  country TEXT,
  source_uri TEXT NOT NULL,
  source_type TEXT NOT NULL,
  checksum TEXT NOT NULL,
  file_checksum TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chunks (
  id TEXT NOT NULL PRIMARY KEY,
  document_id TEXT NOT NULL REFERENCES documents(id),
  content TEXT NOT NULL,
  page_number INTEGER,
  position INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS chunks_document_id ON chunks(document_id, position);

CREATE TABLE IF NOT EXISTS document_hazards (
  document_id TEXT NOT NULL REFERENCES documents(id),
  hazard TEXT NOT NULL,
  PRIMARY KEY (document_id, hazard)
);

CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
  chunk_id UNINDEXED,
  content
);

CREATE TRIGGER IF NOT EXISTS chunks_fts_insert AFTER INSERT ON chunks BEGIN
  INSERT INTO chunks_fts(chunk_id, content) VALUES (new.id, new.content);
END;

CREATE TRIGGER IF NOT EXISTS chunks_fts_delete AFTER DELETE ON chunks BEGIN
  DELETE FROM chunks_fts WHERE chunk_id = old.id;
END;

CREATE TABLE IF NOT EXISTS schema_version (
  id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
  bundle_version TEXT NOT NULL,
  format_version INTEGER NOT NULL,
  sequence INTEGER NOT NULL,
  last_sync_epoch_seconds INTEGER
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);
''';

void _clearContent(SqlDatabase database) {
  database.exec('''
    DELETE FROM chunks;
    DELETE FROM document_hazards;
    DELETE FROM documents;
    DELETE FROM schema_version;
  ''');
}

void _insertContent(
  SqlDatabase database,
  BundleManifest manifest,
  Map<String, DocumentFile> files,
) {
  for (final document in manifest.documents) {
    database.run(
      'INSERT INTO documents('
      'id, title, agency, published_date, reviewed_on, expires_on, licence, '
      'country, source_uri, source_type, checksum, file_checksum'
      ') VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        document.id,
        document.title,
        document.agency,
        document.publishedDate,
        document.reviewedOn,
        document.expiresOn,
        document.licence,
        document.country,
        document.sourceUri,
        document.sourceType,
        document.checksum,
        document.fileChecksum,
      ],
    );
    for (final hazard in document.hazards.toSet()) {
      database.run(
        'INSERT INTO document_hazards(document_id, hazard) VALUES (?, ?)',
        [document.id, hazard],
      );
    }
    final file = files[document.id]!;
    for (var position = 0; position < file.chunks.length; position++) {
      final chunk = file.chunks[position];
      database.run(
        'INSERT INTO chunks('
        'id, document_id, content, page_number, position'
        ') VALUES (?, ?, ?, ?, ?)',
        [chunk.id, document.id, chunk.content, chunk.pageNumber, position],
      );
    }
  }
  database.run(
    'INSERT INTO schema_version(id, bundle_version, format_version, sequence) VALUES (1, ?, ?, ?)',
    [manifest.bundleVersion, manifest.formatVersion, manifest.sequence],
  );
}

void _copySettings(SqlDatabase database, List<Map<String, Object?>> settings) {
  for (final row in settings) {
    database.run('INSERT INTO settings(key, value) VALUES (?, ?)', [
      row['key'],
      row['value'],
    ]);
  }
}

void _verifyContent(SqlDatabase database, BundleManifest manifest) {
  final documents = _count(database, 'documents');
  final chunks = _count(database, 'chunks');
  final hazards = _count(database, 'document_hazards');
  final indexed = _count(database, 'chunks_fts');
  final expectedDocuments = manifest.documents.length;
  final expectedChunks = manifest.documents.fold<int>(
    0,
    (sum, document) => sum + document.chunks.length,
  );
  final expectedHazards = manifest.documents.fold<int>(
    0,
    (sum, document) => sum + document.hazards.toSet().length,
  );
  if (documents != expectedDocuments) {
    throw StoreUpdateError(
      'staged $documents documents, manifest lists $expectedDocuments',
    );
  }
  if (chunks != expectedChunks) {
    throw StoreUpdateError(
      'staged $chunks chunks, manifest lists $expectedChunks',
    );
  }
  if (hazards != expectedHazards) {
    throw StoreUpdateError(
      'staged $hazards document hazards, manifest lists $expectedHazards',
    );
  }
  if (indexed != chunks) {
    throw StoreUpdateError(
      'keyword index has $indexed rows for $chunks chunks',
    );
  }
}

int _count(SqlDatabase database, String table) {
  final rows = database.all('SELECT count(*) AS n FROM $table');
  return rows.first['n'] as int;
}

StoredDocument _document(Map<String, Object?> row, List<String> hazards) {
  return StoredDocument(
    id: row['id'] as String,
    title: row['title'] as String,
    agency: row['agency'] as String,
    publishedDate: row['published_date'] as String,
    reviewedOn: row['reviewed_on'] as String,
    expiresOn: row['expires_on'] as String,
    licence: row['licence'] as String,
    country: row['country'] as String?,
    hazards: hazards,
    sourceUri: row['source_uri'] as String,
    sourceType: row['source_type'] as String,
    checksum: row['checksum'] as String,
    fileChecksum: row['file_checksum'] as String,
  );
}

StoredChunk _chunk(Map<String, Object?> row) {
  return StoredChunk(
    id: row['id'] as String,
    documentId: row['document_id'] as String,
    content: row['content'] as String,
    pageNumber: row['page_number'] as int?,
    position: row['position'] as int,
  );
}

final _word = RegExp(r'[\p{L}\p{N}]+', unicode: true);

/// Quotes each distinct word so the MATCH expression cannot treat user text
/// as FTS5 syntax. Returns null when [text] has no words.
String? _ftsQueryFor(String text) {
  final quoted = <String>[];
  final seen = <String>{};
  for (final match in _word.allMatches(text)) {
    final word = match.group(0)!;
    if (seen.add(word)) {
      quoted.add('"$word"');
    }
  }
  if (quoted.isEmpty) {
    return null;
  }
  return quoted.join(' OR ');
}
