import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('modelEvalDocNamesDeviceSha256AndResult', () {
    final doc = File('../../docs/model-eval.md');
    expect(
      doc.existsSync(),
      isTrue,
      reason: 'docs/model-eval.md not found from ${Directory.current.path}',
    );
    final text = doc.readAsStringSync();
    expect(text, contains('device'));
    expect(text, contains('sha256'));
    expect(text, contains('result'));
  });
}
