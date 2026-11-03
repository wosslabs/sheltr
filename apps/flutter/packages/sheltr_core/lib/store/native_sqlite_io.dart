import 'package:sqlite3/common.dart';
import 'package:sqlite3/sqlite3.dart';

/// Opens a database with the bundled sqlite3 build. FTS5 is enabled.
CommonDatabase openBundled(String path) => sqlite3.open(path);
