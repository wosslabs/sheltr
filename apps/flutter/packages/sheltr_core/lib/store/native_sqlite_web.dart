import 'package:sqlite3/common.dart';

/// Web passes a driver that already loaded sqlite3.wasm.
CommonDatabase openBundled(String path) => throw UnsupportedError(
  'Load sqlite3.wasm and pass a driver before opening $path',
);
