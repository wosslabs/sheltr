import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/dart.dart';
import 'package:sqlite3/common.dart';

import '../bundle/format.dart';
import 'native_sqlite_web.dart'
    if (dart.library.io) 'native_sqlite_io.dart'
    if (dart.library.js_interop) 'native_sqlite_web.dart';
import 'store_files.dart';
import 'store_files_web.dart'
    if (dart.library.io) 'store_files_io.dart'
    if (dart.library.js_interop) 'store_files_web.dart';

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

class StoredCard {
  const StoredCard({
    required this.id,
    required this.hazard,
    required this.phase,
    required this.country,
    required this.title,
    required this.steps,
  });

  final String id;
  final String hazard;

  /// `before`, `during`, or `after`.
  final String phase;
  final String? country;
  final String title;
  final List<StoredCardStep> steps;
}

class StoredCardStep {
  const StoredCardStep({
    required this.text,
    required this.documentId,
    required this.chunkId,
  });

  /// Verbatim substring of the cited chunk.
  final String text;
  final String documentId;
  final String chunkId;
}

class SearchHit {
  const SearchHit({required this.chunk, required this.score});

  final StoredChunk chunk;

  /// FTS5 bm25 score. Lower is a better match.
  final double score;
}

/// One chunk whose embedding cleared the cosine floor. Higher [score] is closer.
class VectorHit {
  const VectorHit({required this.chunkId, required this.score});

  final String chunkId;
  final double score;
}

/// Little-endian float32 bytes. The vector table stores these blobs.
Uint8List packFloat32(List<double> values) {
  final bytes = Uint8List(values.length * 4);
  final data = ByteData.sublistView(bytes);
  for (var i = 0; i < values.length; i++) {
    data.setFloat32(i * 4, values[i], Endian.little);
  }
  return bytes;
}

List<double> unpackFloat32(Uint8List bytes) {
  final data = ByteData.sublistView(bytes);
  return [
    for (var i = 0; i < bytes.length; i += 4)
      data.getFloat32(i, Endian.little),
  ];
}

/// Cosine similarity in \[-1, 1\]. A zero-length vector scores 0.
double cosineSimilarity(List<double> left, List<double> right) {
  if (left.length != right.length || left.isEmpty) {
    return 0;
  }
  var dot = 0.0;
  var leftNorm = 0.0;
  var rightNorm = 0.0;
  for (var i = 0; i < left.length; i++) {
    dot += left[i] * right[i];
    leftNorm += left[i] * left[i];
    rightNorm += right[i] * right[i];
  }
  if (leftNorm == 0 || rightNorm == 0) {
    return 0;
  }
  return dot / (sqrt(leftNorm) * sqrt(rightNorm));
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
  SqlDatabase open(String path) => BundledSqlDatabase(openBundled(path));
}

/// One complete bundle in `content.db`.
///
/// [install] writes the new bundle into `content.db.staging`, checks its row
/// counts, closes that file, and renames it over `content.db`. The active
/// file is not modified until that rename. Any throw before the rename deletes
/// the staging file and leaves the previous bundle in place.
class ContentStore {
  ContentStore._(this._root, this._files, this._driver, this._db);

  /// Opens [directoryPath]/`content.db`, creating the schema if needed.
  ///
  /// A leftover `content.db.staging` from an interrupted install is deleted
  /// first, along with its `-journal`, `-wal`, and `-shm` sidecars.
  ///
  /// [files] and [driver] have defaults on the phone. The web UI test passes
  /// an in-memory filesystem and a wasm driver that share that filesystem.
  factory ContentStore.open(
    String directoryPath, {
    StoreFiles? files,
    SqlDriver? driver,
  }) {
    final resolvedFiles = files ?? defaultStoreFiles();
    final resolved = driver ?? const SqliteDriver();
    _deleteStaging(directoryPath, resolvedFiles);
    _deleteModelStaging(directoryPath, resolvedFiles);
    final db = resolved.open(_path(directoryPath, _contentFile));
    try {
      db.exec(_schemaSql);
      _ensureReviewColumns(db);
    } catch (error) {
      db.close();
      rethrow;
    }
    return ContentStore._(directoryPath, resolvedFiles, resolved, db);
  }

  final String _root;
  final StoreFiles _files;
  final SqlDriver _driver;
  SqlDatabase _db;
  var _closed = false;

  String get _contentPath => _path(_root, _contentFile);

  String get _stagingPath => _path(_root, _stagingFile);

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

  /// Cards in bundle order. Steps of each card stay in their own order.
  List<StoredCard> cards() {
    final cardRows = _db.all('SELECT * FROM cards ORDER BY position, id');
    final stepRows = _db.all(
      'SELECT * FROM card_steps ORDER BY card_id, position',
    );
    final byCard = <String, List<StoredCardStep>>{};
    for (final row in stepRows) {
      final id = row['card_id'] as String;
      byCard
          .putIfAbsent(id, () => [])
          .add(
            StoredCardStep(
              text: row['text'] as String,
              documentId: row['document_id'] as String,
              chunkId: row['chunk_id'] as String,
            ),
          );
    }
    return [
      for (final row in cardRows)
        StoredCard(
          id: row['id'] as String,
          hazard: row['hazard'] as String,
          phase: row['phase'] as String,
          country: row['country'] as String?,
          title: row['title'] as String,
          steps: List<StoredCardStep>.unmodifiable(
            byCard[row['id'] as String] ?? const [],
          ),
        ),
    ];
  }

  List<StoredChunk> chunks(String documentId) {
    final rows = _db.all(
      'SELECT * FROM chunks WHERE document_id = ? ORDER BY position',
      [documentId],
    );
    return [for (final row in rows) _chunk(row)];
  }

  /// Every stored chunk, in document and position order.
  List<StoredChunk> allChunks() {
    final rows = _db.all(
      'SELECT * FROM chunks ORDER BY document_id, position',
    );
    return [for (final row in rows) _chunk(row)];
  }

  int vectorCount() {
    final rows = _db.all('SELECT count(*) AS n FROM chunk_vectors');
    return rows.first['n'] as int;
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

  /// Replaces the vector index in one transaction.
  ///
  /// A crash before commit leaves the previous vectors. An empty map, a
  /// zero-length vector, or mixed dimensions is rejected before the delete.
  /// A new bundle install builds a fresh database, so these rows do not
  /// survive into the next corpus.
  void replaceVectors(Map<String, List<double>> vectors) {
    if (vectors.isEmpty) {
      throw ArgumentError('vectors must not be empty');
    }
    final dimension = vectors.values.first.length;
    if (dimension <= 0) {
      throw ArgumentError('vector dimension must be positive');
    }
    for (final vector in vectors.values) {
      if (vector.length != dimension) {
        throw ArgumentError('vector dimensions differ');
      }
    }
    _db.exec('BEGIN IMMEDIATE');
    try {
      _db.exec('DELETE FROM chunk_vectors');
      for (final entry in vectors.entries) {
        _db.run(
          'INSERT INTO chunk_vectors(chunk_id, dim, vector) VALUES (?, ?, ?)',
          [entry.key, dimension, packFloat32(entry.value)],
        );
      }
      _db.exec('COMMIT');
    } catch (error) {
      try {
        _db.exec('ROLLBACK');
      } on Object {
        // Keep the original failure.
      }
      rethrow;
    }
  }

  /// Chunks whose embedding is at least [minScore] similar to [query].
  ///
  /// Rows with a different dimension are skipped. [limit] must be positive.
  List<VectorHit> searchVectors(
    List<double> query, {
    int limit = 8,
    double minScore = 0,
  }) {
    if (limit <= 0) {
      throw RangeError('limit must be positive');
    }
    if (query.isEmpty) {
      return const [];
    }
    final rows = _db.all('SELECT chunk_id, dim, vector FROM chunk_vectors');
    final hits = <VectorHit>[];
    for (final row in rows) {
      if (row['dim'] != query.length) {
        continue;
      }
      final raw = row['vector'];
      if (raw is! Uint8List || raw.lengthInBytes != query.length * 4) {
        continue;
      }
      final score = cosineSimilarity(query, unpackFloat32(raw));
      if (score < minScore) {
        continue;
      }
      hits.add(VectorHit(chunkId: row['chunk_id'] as String, score: score));
    }
    hits.sort((a, b) => b.score.compareTo(a.score));
    if (hits.length <= limit) {
      return hits;
    }
    return hits.sublist(0, limit);
  }

  StoredChunk? chunkById(String id) {
    final rows = _db.all('SELECT * FROM chunks WHERE id = ?', [id]);
    if (rows.isEmpty) {
      return null;
    }
    return _chunk(rows.first);
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

  /// The verified model file, or null when this store has none.
  InstalledModel? installedModel() {
    final file = _path(_root, _modelFile);
    final meta = _path(_root, _modelMetaFile);
    if (!_files.exists(file) || !_files.exists(meta)) {
      return null;
    }
    final Object? decoded;
    try {
      decoded = jsonDecode(_files.readString(meta));
    } on FormatException {
      return null;
    }
    if (decoded is! Map) {
      return null;
    }
    final sha256 = decoded['sha256'];
    final byteSize = decoded['byteSize'];
    final modelType = decoded['modelType'];
    if (sha256 is! String || byteSize is! int || modelType is! String) {
      return null;
    }
    // A torn write can leave new bytes beside an old record, or the reverse.
    // The length is checked before the record is trusted.
    if (_files.length(file) != byteSize) {
      return null;
    }
    return InstalledModel(
      path: file,
      sha256: sha256,
      byteSize: byteSize,
      modelType: modelType,
    );
  }

  /// Streams [chunks] onto the model staging file.
  ///
  /// The SHA-256 runs as each chunk is written. The stream is cancelled when
  /// the next chunk would pass [model.byteSize], and that chunk is not
  /// written. The rest of the body is not read. The staging file is renamed
  /// into place only when its length and digest match [model]. A mismatch
  /// deletes the staging file and leaves the previous verified model in place.
  ///
  /// The previous sidecar is removed before the new bytes are renamed into
  /// place. A crash between that rename and the sidecar rename then has no
  /// old sha256 to pair with the new file.
  Future<bool> commitModelStream(
    Stream<List<int>> chunks,
    BundleModel model,
  ) async {
    final staging = _path(_root, _modelStagingFile);
    final target = _path(_root, _modelFile);
    final metaStaging = _path(_root, _modelMetaStagingFile);
    final metaTarget = _path(_root, _modelMetaFile);
    _deleteModelStaging(_root, _files);
    final hash = _Sha256Sink();
    StreamSink<List<int>>? sink;
    try {
      final output = _files.openWrite(staging);
      sink = output;
      var written = 0;
      var tooBig = false;
      final incoming = StreamIterator<List<int>>(chunks);
      try {
        while (await incoming.moveNext()) {
          final chunk = incoming.current;
          if (chunk.isEmpty) {
            continue;
          }
          if (written + chunk.length > model.byteSize) {
            tooBig = true;
            break;
          }
          output.add(chunk);
          hash.add(chunk);
          written += chunk.length;
        }
      } finally {
        try {
          await incoming.cancel();
        } on Object {
          // The cap decision or the stream error is the one to report.
        }
      }
      await output.close();
      sink = null;
      if (tooBig ||
          written != model.byteSize ||
          _files.length(staging) != model.byteSize ||
          hash.closeHex() != model.sha256) {
        _deleteModelStaging(_root, _files);
        return false;
      }
      _files.writeString(
        metaStaging,
        jsonEncode({
          'file': model.file,
          'sha256': model.sha256,
          'byteSize': model.byteSize,
          'modelType': model.modelType,
        }),
      );
      if (_files.exists(metaTarget)) {
        _files.delete(metaTarget);
      }
      _files.rename(staging, target);
      _files.rename(metaStaging, metaTarget);
      return true;
    } on Object {
      if (sink != null) {
        try {
          await sink.close();
        } on Object {
          // The original failure is the one to report.
        }
      }
      _deleteModelStaging(_root, _files);
      rethrow;
    }
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
    _deleteStaging(_root, _files);

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
      _files.rename(_stagingPath, _contentPath);
      renamed = true;
      _db = _openContent();
      activeClosed = false;
      // The rename moves only the database file. Drop a journal or wal that
      // SQLite left beside the staging name.
      _deleteStaging(_root, _files);
    } catch (_) {
      _closeQuietly(staging);
      if (!renamed) {
        _deleteStaging(_root, _files);
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

class BundledSqlDatabase implements SqlDatabase {
  BundledSqlDatabase(this._db);

  final CommonDatabase _db;
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
const _modelFile = 'verified-model';
const _modelStagingFile = 'verified-model.staging';
const _modelMetaFile = 'model.json';
const _modelMetaStagingFile = 'model.json.staging';

/// The model file [ContentStore] keeps beside `content.db` after its checksum
/// matches the signed manifest.
class InstalledModel {
  const InstalledModel({
    required this.path,
    required this.sha256,
    required this.byteSize,
    required this.modelType,
  });

  /// Absolute path of the verified model file.
  final String path;
  final String sha256;
  final int byteSize;
  final String modelType;
}

const _stagingFiles = [
  _stagingFile,
  '$_stagingFile-journal',
  '$_stagingFile-wal',
  '$_stagingFile-shm',
];

String _path(String root, String name) =>
    root.endsWith('/') ? '$root$name' : '$root/$name';

/// SHA-256 over chunks. The chunks are not retained.
class _Sha256Sink {
  final DartHashSink _sink = const DartSha256().newHashSink();

  void add(List<int> chunk) {
    _sink.add(chunk);
  }

  String closeHex() {
    _sink.close();
    return _hex(_sink.hashSync().bytes);
  }
}

String _hex(List<int> bytes) {
  final hex = StringBuffer();
  for (final byte in bytes) {
    hex.write(byte.toRadixString(16).padLeft(2, '0'));
  }
  return hex.toString();
}

void _deleteStaging(String root, StoreFiles files) {
  for (final name in _stagingFiles) {
    files.delete(_path(root, name));
  }
}

void _deleteModelStaging(String root, StoreFiles files) {
  for (final name in [_modelStagingFile, _modelMetaStagingFile]) {
    files.delete(_path(root, name));
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

CREATE TABLE IF NOT EXISTS cards (
  id TEXT NOT NULL PRIMARY KEY,
  hazard TEXT NOT NULL,
  phase TEXT NOT NULL,
  country TEXT,
  title TEXT NOT NULL,
  position INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS card_steps (
  card_id TEXT NOT NULL REFERENCES cards(id),
  position INTEGER NOT NULL,
  text TEXT NOT NULL,
  document_id TEXT NOT NULL,
  chunk_id TEXT NOT NULL,
  PRIMARY KEY (card_id, position)
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

CREATE TABLE IF NOT EXISTS chunk_vectors (
  chunk_id TEXT NOT NULL PRIMARY KEY,
  dim INTEGER NOT NULL,
  vector BLOB NOT NULL
);

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
    DELETE FROM card_steps;
    DELETE FROM cards;
    DELETE FROM chunks;
    DELETE FROM chunk_vectors;
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
  for (var position = 0; position < manifest.cards.length; position++) {
    final card = manifest.cards[position];
    database.run(
      'INSERT INTO cards(id, hazard, phase, country, title, position) '
      'VALUES (?, ?, ?, ?, ?, ?)',
      [card.id, card.hazard, card.phase, card.country, card.title, position],
    );
    for (var stepIndex = 0; stepIndex < card.steps.length; stepIndex++) {
      final step = card.steps[stepIndex];
      database.run(
        'INSERT INTO card_steps('
        'card_id, position, text, document_id, chunk_id'
        ') VALUES (?, ?, ?, ?, ?)',
        [card.id, stepIndex, step.text, step.documentId, step.chunkId],
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
  final cards = _count(database, 'cards');
  final cardSteps = _count(database, 'card_steps');
  final expectedDocuments = manifest.documents.length;
  final expectedChunks = manifest.documents.fold<int>(
    0,
    (sum, document) => sum + document.chunks.length,
  );
  final expectedHazards = manifest.documents.fold<int>(
    0,
    (sum, document) => sum + document.hazards.toSet().length,
  );
  final expectedCards = manifest.cards.length;
  final expectedSteps = manifest.cards.fold<int>(
    0,
    (sum, card) => sum + card.steps.length,
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
  if (cards != expectedCards) {
    throw StoreUpdateError(
      'staged $cards cards, manifest lists $expectedCards',
    );
  }
  if (cardSteps != expectedSteps) {
    throw StoreUpdateError(
      'staged $cardSteps card steps, manifest lists $expectedSteps',
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
