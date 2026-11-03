import 'store_files.dart';

/// The web test passes its own files, sharing them with the wasm filesystem.
StoreFiles defaultStoreFiles() => throw UnsupportedError(
  'The web UI test opens the store with an in-memory filesystem',
);
