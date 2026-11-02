import 'dart:io';

import 'package:test/test.dart';

void main() {
  test('lib does not contain package:flutter', () {
    final lib = Directory('lib');
    expect(lib.existsSync(), isTrue, reason: 'sheltr_core/lib is missing');

    final hits = <String>[];
    for (final entity in lib.listSync(recursive: true, followLinks: false)) {
      if (entity is! File) {
        continue;
      }
      if (entity.readAsStringSync().contains('package:flutter')) {
        hits.add(entity.path);
      }
    }

    expect(
      hits,
      isEmpty,
      reason: 'package:flutter found in: ${hits.join(', ')}',
    );
  });
}
