import 'dart:io';

/// `contract/fixtures/<name>`, found by walking up from the test process.
Directory fixtureDirectory(String name) {
  var dir = Directory.current;
  while (true) {
    final candidate = Directory('${dir.path}/contract/fixtures/$name');
    if (candidate.existsSync()) {
      return candidate;
    }
    final parent = dir.parent;
    if (parent.path == dir.path) {
      throw StateError(
        'contract/fixtures/$name not found from ${Directory.current.path}',
      );
    }
    dir = parent;
  }
}
