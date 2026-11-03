import 'dart:io';

import 'package:sheltr_core/bundle/parser.dart';
import 'package:sheltr_core/retrieval/retriever.dart';
import 'package:sheltr_core/settings/countries.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:test/test.dart';

void main() {
  test('a selected country hides other countries and keeps its own text', () {
    expect(countryFromLocaleTag('us'), 'US');
    expect(countryFromLocaleTag('gb'), 'GB');
    expect(countryFromLocaleTag(''), isNull);
    expect(countryFromLocaleTag('ZZ'), isNull);

    final directory = Directory.systemTemp.createTempSync('sheltr-country-');
    final store = ContentStore.open(directory.path);
    try {
      _installSample(store);
      const question = 'what should I do during a wildfire';
      final britain = Retriever(store).retrieve(
        const RetrievalQuery(text: question, country: 'GB'),
      );
      expect(britain.kind, 'passages');
      expect(
        britain.passages.every((passage) => passage.document.country == 'GB'),
        isTrue,
      );

      final unitedStates = Retriever(store).retrieve(
        const RetrievalQuery(text: question, country: 'US'),
      );
      expect(unitedStates.kind, 'passages');
      expect(
        unitedStates.passages.every(
          (passage) => passage.document.country == null,
        ),
        isTrue,
      );
    } finally {
      store.close();
      directory.deleteSync(recursive: true);
    }
  });
}

void _installSample(ContentStore store) {
  final root = _findUp('corpus/sample-bundle');
  final manifest = parseBundleManifest(
    File('${root.path}/bundle.json').readAsStringSync(),
  );
  store.install(manifest, (document) {
    return parseDocumentFileBytes(
      File('${root.path}/documents/${document.id}.json').readAsBytesSync(),
    );
  });
}

Directory _findUp(String relative) {
  var dir = Directory.current;
  while (true) {
    final candidate = Directory('${dir.path}/$relative');
    if (candidate.existsSync()) {
      return candidate;
    }
    final parent = dir.parent;
    if (parent.path == dir.path) {
      throw StateError('$relative not found from ${Directory.current.path}');
    }
    dir = parent;
  }
}
