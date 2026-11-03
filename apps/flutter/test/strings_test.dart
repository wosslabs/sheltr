import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('every contract key and placeholder is in each locale', () {
    final contract = File('../../contract/strings.tsv');
    expect(
      contract.existsSync(),
      isTrue,
      reason: 'contract/strings.tsv not found from ${Directory.current.path}',
    );
    final lines = contract.readAsLinesSync();
    expect(lines.first.split('\t'), ['key', 'placeholders']);

    final catalogs = {
      for (final locale in ['en', 'es', 'fr'])
        locale: _catalog('lib/l10n/app_$locale.arb'),
    };

    for (final line in lines.skip(1)) {
      if (line.trim().isEmpty) {
        continue;
      }
      final parts = line.split('\t');
      final key = parts[0];
      final placeholders = parts.length < 2
          ? const <String>[]
          : parts[1].split(RegExp(r'\s+')).where((token) => token.isNotEmpty);

      for (final entry in catalogs.entries) {
        final value = entry.value[key];
        expect(value, isA<String>(), reason: '${entry.key} is missing $key');
        final text = (value! as String).trim();
        expect(text, isNotEmpty, reason: '${entry.key} $key is empty');
        for (final placeholder in placeholders) {
          expect(
            text.contains(placeholder),
            isTrue,
            reason: '${entry.key} $key is missing $placeholder',
          );
        }
      }
    }
  });
}

Map<String, dynamic> _catalog(String path) {
  final file = File(path);
  expect(
    file.existsSync(),
    isTrue,
    reason: '$path is missing (cwd ${Directory.current.path})',
  );
  final decoded = jsonDecode(file.readAsStringSync());
  expect(decoded, isA<Map<String, dynamic>>());
  return decoded as Map<String, dynamic>;
}
