import 'dart:io';

import 'package:sheltr_core/sheltr_core.dart';
import 'package:test/test.dart';

void main() {
  test('model cases match the contract', () async {
    final rows = _loadModelCases();
    expect(rows, isNotEmpty);

    final expects = rows.map((row) => row.expect).toSet();
    expect(expects, containsAll(['show', 'hide', 'nocall']));

    var shortened = false;
    var keptDigit = false;
    var addedWord = false;
    var changedDigit = false;
    var thirtyHidesThree = false;
    var tenHidesOne = false;
    var none = false;
    var tooLong = false;
    var refusal = false;

    for (final row in rows) {
      final cited = [CitedPassage(row.passages)];
      if (row.expect == 'nocall') {
        refusal = true;
        final model = _CountingModel(row.model);
        final retrieval = const RetrievalResult.refuse();
        final presentation = await presentWithModel(
          model: model,
          question: row.question,
          retrieval: retrieval,
          modelEnabled: true,
        );
        expect(model.calls, 0, reason: row.question);
        expect(presentation.lead, isNull);
        expect(presentation.passages, isEmpty);
        continue;
      }

      final supported = modelTextIsSupported(row.model, cited);
      expect(
        supported,
        row.expect == 'show',
        reason: '${row.expect}: ${row.model}',
      );

      final retrieval = RetrievalResult.passages([_passage(row.passages)]);
      final presentation = present(
        retrieval: retrieval,
        modelText: row.model,
        modelEnabled: true,
      );
      expect(presentation.passages, same(retrieval.passages));
      if (row.expect == 'show') {
        expect(presentation.lead, row.model);
      } else {
        expect(presentation.lead, isNull);
      }

      if (row.model == 'Boil water before drinking.') {
        shortened = true;
      }
      if (row.model.endsWith(' 72') && row.expect == 'show') {
        keptDigit = true;
      }
      if (row.model.contains('chlorine')) {
        addedWord = true;
      }
      if (row.passages.endsWith(' 72') &&
          row.model.endsWith(' 3') &&
          row.expect == 'hide') {
        changedDigit = true;
      }
      if (row.passages.endsWith(' 30') &&
          row.model.endsWith(' 3') &&
          row.expect == 'hide') {
        thirtyHidesThree = true;
      }
      if (row.passages.endsWith(' 10') &&
          row.model.endsWith(' 1') &&
          row.expect == 'hide') {
        tenHidesOne = true;
      }
      if (row.model == 'NONE') {
        none = true;
      }
      if (row.model.length > 600) {
        tooLong = true;
      }
    }

    expect(shortened, isTrue, reason: 'shortened sentence row missing');
    expect(keptDigit, isTrue, reason: 'kept 72 row missing');
    expect(addedWord, isTrue, reason: 'added content word row missing');
    expect(changedDigit, isTrue, reason: '72 changed to 3 row missing');
    expect(thirtyHidesThree, isTrue, reason: '30 does not support 3');
    expect(tenHidesOne, isTrue, reason: '10 does not support 1');
    expect(none, isTrue, reason: 'NONE row missing');
    expect(tooLong, isTrue, reason: 'row longer than 600 characters missing');
    expect(refusal, isTrue, reason: 'retrieval refusal row missing');
  });

  test('hides model text that uses non-ASCII digits', () {
    const passage = 'Call 112';
    final cited = [CitedPassage(passage)];
    final retrieval = RetrievalResult.passages([_passage(passage)]);
    const models = ['Call \uFF19\uFF11\uFF11', 'Call \u0669\u0661\u0661'];
    for (final modelText in models) {
      expect(
        modelTextIsSupported(modelText, cited),
        isFalse,
        reason: modelText,
      );
      final presentation = present(
        retrieval: retrieval,
        modelText: modelText,
        modelEnabled: true,
      );
      expect(presentation.lead, isNull, reason: modelText);
      expect(presentation.passages, same(retrieval.passages));
    }
  });

  test('hides a timing word the passages do not support', () {
    const passage = 'Turn off the gas before you leave the house';
    final cited = [CitedPassage(passage)];
    final retrieval = RetrievalResult.passages([_passage(passage)]);
    const hidden = [
      'after you leave the house',
      'during you leave the house',
      'until you leave the house',
      'under you leave the house',
      'over you leave the house',
      'more you leave the house',
      'once you leave the house',
      'only you leave the house',
    ];
    for (final modelText in hidden) {
      expect(
        modelTextIsSupported(modelText, cited),
        isFalse,
        reason: modelText,
      );
      final presentation = present(
        retrieval: retrieval,
        modelText: modelText,
        modelEnabled: true,
      );
      expect(presentation.lead, isNull, reason: modelText);
      expect(presentation.passages, same(retrieval.passages));
    }
    expect(modelTextIsSupported('before you leave the house', cited), isTrue);
    expect(
      modelTextIsSupported('before you leave the house', [
        CitedPassage('Turn off the gas and leave the house'),
      ]),
      isFalse,
    );
    expect(
      modelTextIsSupported('Leave the house once', [
        CitedPassage('Leave the house once'),
      ]),
      isTrue,
    );
  });

  test('blank or NONE model text is hidden', () {
    final cited = [CitedPassage(_quote)];
    expect(modelTextIsSupported('   ', cited), isFalse);
    expect(modelTextIsSupported(' NONE ', cited), isFalse);
    final refused = present(
      retrieval: const RetrievalResult.refuse(),
      modelText: 'Boil water before drinking.',
      modelEnabled: true,
    );
    expect(refused.lead, isNull);
    expect(refused.passages, isEmpty);
  });

  test('disabled model is not called and the passages stay', () async {
    final model = _CountingModel('Boil water before drinking.');
    final retrieval = RetrievalResult.passages([_passage(_quote)]);
    final presentation = await presentWithModel(
      model: model,
      question: 'what should I do before drinking water',
      retrieval: retrieval,
      modelEnabled: false,
    );
    expect(model.calls, 0);
    expect(presentation.lead, isNull);
    expect(presentation.passages, same(retrieval.passages));
    expect(AppSettings.defaults.modelEnabled, isFalse);
    expect(parseSettings(null).modelEnabled, isFalse);
    expect(parseSettings('{"modelEnabled":"yes"}').modelEnabled, isFalse);
    expect(parseSettings('{"modelEnabled":true}').modelEnabled, isTrue);
    expect(
      AppSettings.defaults.copyWith(modelEnabled: true).modelEnabled,
      isTrue,
    );
    expect(AppSettings.defaults.modelEnabled, isFalse);
  });

  test(
    'enabled model is called for passages and a supported lead shows',
    () async {
      final model = _CountingModel('Boil water before drinking.');
      final retrieval = RetrievalResult.passages([_passage(_quote)]);
      final presentation = await presentWithModel(
        model: model,
        question: 'what should I do before drinking water',
        retrieval: retrieval,
        modelEnabled: true,
      );
      expect(model.calls, 1);
      expect(model.question, 'what should I do before drinking water');
      expect(model.seen?.single.text, _quote);
      expect(presentation.lead, 'Boil water before drinking.');
      expect(presentation.passages, same(retrieval.passages));
    },
  );

  test('sheltr_core does not import LiteRT', () {
    final lib = Directory('lib');
    expect(lib.existsSync(), isTrue);
    final hits = <String>[];
    for (final entity in lib.listSync(recursive: true, followLinks: false)) {
      if (entity is! File || !entity.path.endsWith('.dart')) {
        continue;
      }
      final source = entity.readAsStringSync();
      if (source.contains('flutter_edge_ai') || source.contains('litert')) {
        hits.add(entity.path);
      }
    }
    expect(hits, isEmpty, reason: hits.join(', '));
  });
}

const _quote = 'Boil water for one minute before drinking.';

class _CountingModel implements PassageModel {
  _CountingModel(this.text);

  final String text;
  var calls = 0;
  String? question;
  List<CitedPassage>? seen;

  @override
  Future<String> rewrite(String question, List<CitedPassage> passages) async {
    calls += 1;
    this.question = question;
    seen = passages;
    return text;
  }
}

Passage _passage(String content) {
  return Passage(
    chunk: StoredChunk(
      id: 'boil-0',
      documentId: 'boil',
      content: content,
      pageNumber: null,
      position: 0,
    ),
    document: const StoredDocument(
      id: 'boil',
      title: 'Boil water',
      agency: 'Example Agency',
      publishedDate: '2024-03-15',
      reviewedOn: '2026-10-05',
      expiresOn: '2027-11-02',
      licence: 'CC-BY-4.0',
      country: 'NO',
      hazards: ['FLOOD'],
      sourceUri: 'https://example.org/boil-water.html',
      sourceType: 'html',
      checksum: '0',
      fileChecksum: '1',
    ),
    score: 2,
    rankScore: 2,
  );
}

class _ModelCase {
  const _ModelCase(this.question, this.passages, this.model, this.expect);

  final String question;
  final String passages;
  final String model;
  final String expect;
}

List<_ModelCase> _loadModelCases() {
  final rows = <_ModelCase>[];
  for (final raw in _findUp('contract/model-cases.tsv').readAsLinesSync()) {
    final line = raw.trimRight();
    if (line.isEmpty || line.startsWith('#') || line.startsWith('question\t')) {
      continue;
    }
    final parts = line.split('\t');
    if (parts.length != 4) {
      throw FormatException('bad model case: $raw');
    }
    rows.add(_ModelCase(parts[0], parts[1], parts[2], parts[3]));
  }
  return rows;
}

File _findUp(String relative) {
  var dir = Directory.current;
  while (true) {
    final candidate = File('${dir.path}/$relative');
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
