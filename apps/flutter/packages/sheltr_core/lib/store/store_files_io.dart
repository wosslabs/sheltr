import 'dart:async';
import 'dart:io';

import 'store_files.dart';

/// Files on the device filesystem.
class IoStoreFiles implements StoreFiles {
  const IoStoreFiles();

  @override
  bool exists(String path) => File(path).existsSync();

  @override
  int length(String path) => File(path).lengthSync();

  @override
  String readString(String path) => File(path).readAsStringSync();

  @override
  void writeString(String path, String text) {
    File(path).writeAsStringSync(text, flush: true);
  }

  @override
  StreamSink<List<int>> openWrite(String path) => File(path).openWrite();

  @override
  void delete(String path) {
    final file = File(path);
    if (file.existsSync()) {
      file.deleteSync();
    }
  }

  @override
  void rename(String from, String to) {
    File(from).renameSync(to);
  }
}

StoreFiles defaultStoreFiles() => const IoStoreFiles();
