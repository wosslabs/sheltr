import 'dart:async';
import 'dart:convert';

import 'package:sheltr_core/sheltr_core.dart';
import 'package:typed_data/typed_buffers.dart';

/// Files stored in the same map the in-memory SQLite filesystem uses.
class MemoryStoreFiles implements StoreFiles {
  MemoryStoreFiles(this.data);

  final Map<String, Uint8Buffer?> data;

  @override
  bool exists(String path) => data[path] != null;

  @override
  int length(String path) => data[path]?.length ?? 0;

  @override
  String readString(String path) => utf8.decode(data[path] ?? const []);

  @override
  void writeString(String path, String text) {
    data[path] = Uint8Buffer()..addAll(utf8.encode(text));
  }

  @override
  StreamSink<List<int>> openWrite(String path) {
    final buffer = Uint8Buffer();
    return _BufferSink(buffer, () => data[path] = buffer);
  }

  @override
  void delete(String path) {
    data.remove(path);
  }

  @override
  void rename(String from, String to) {
    final bytes = data.remove(from);
    if (bytes != null) {
      data[to] = bytes;
    }
  }
}

class _BufferSink implements StreamSink<List<int>> {
  _BufferSink(this._buffer, this._onClose);

  final Uint8Buffer _buffer;
  final void Function() _onClose;
  var _closed = false;

  @override
  void add(List<int> event) {
    _buffer.addAll(event);
  }

  @override
  void addError(Object error, [StackTrace? stackTrace]) {}

  @override
  Future<void> addStream(Stream<List<int>> stream) async {
    await for (final chunk in stream) {
      add(chunk);
    }
  }

  @override
  Future<void> close() async {
    if (_closed) {
      return;
    }
    _closed = true;
    _onClose();
  }

  @override
  Future<void> get done => Future<void>.value();
}
