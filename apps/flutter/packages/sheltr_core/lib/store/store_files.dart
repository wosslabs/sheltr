import 'dart:async';

/// Files beside the database: the model file and the rename of a staged database.
///
/// Native code uses the real filesystem. The web UI test uses the same map the
/// in-memory SQLite filesystem uses, so a rename is visible to both.
abstract interface class StoreFiles {
  bool exists(String path);

  int length(String path);

  String readString(String path);

  void writeString(String path, String text);

  StreamSink<List<int>> openWrite(String path);

  void delete(String path);

  void rename(String from, String to);
}
