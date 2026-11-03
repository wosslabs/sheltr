import 'dart:convert';
import 'dart:io';

import 'package:sheltr_core/bundle/format.dart';
import 'package:sheltr_core/bundle/parser.dart';
import 'package:sheltr_core/retrieval/citations.dart';
import 'package:sheltr_core/retrieval/retrieval_rules.g.dart';
import 'package:sheltr_core/retrieval/retriever.dart';
import 'package:sheltr_core/retrieval/rules.dart';
import 'package:sheltr_core/retrieval/stopwords.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:test/test.dart';

void main() {
  test('numeric rules accept a json integer', () {
    final rules = Rules.parse(
      '{"relevanceThreshold":1,"minTermCoverage":0.6,"countryBoost":0.15,'
      '"hazardBoost":0.15,"poolMultiplier":5,"stopwords":["the"],'
      '"synonyms":{"quake":"earthquake"},"hazardTerms":["earthquake"],'
      '"disasterTypes":["EARTHQUAKE"]}',
    );
    expect(rules.relevanceThreshold, 1.0);
    expect(rules.relevanceThreshold, isA<double>());
    expect(rules.minTermCoverage, 0.6);
    expect(rules.countryBoost, 0.15);
    expect(rules.hazardBoost, 0.15);
    expect(rules.poolMultiplier, 5.0);
    expect(rules.poolMultiplier, isA<double>());
  });

  test('packaged rules match the contract', () {
    final packaged = copyRetrievalRules();
    final contract = findUpFile('contract/retrieval-rules.json');
    final raw = jsonDecode(contract.readAsStringSync()) as Map<String, dynamic>;
    // The contract stores relevanceThreshold as the JSON integer 1.
    expect(raw['relevanceThreshold'], isA<int>());
    expect(raw['relevanceThreshold'], 1);
    expect(packagedRetrievalRulesJson, contract.readAsStringSync());

    final rules = Rules.parse(packaged);
    expect(rules.relevanceThreshold, 1.0);
    expect(Rules.packaged().relevanceThreshold, rules.relevanceThreshold);
    expect(Rules.packaged().minTermCoverage, 0.6);
    expect(Rules.packaged().stopwords, contains('the'));
    expect(Rules.packaged().synonyms['quake'], 'earthquake');
    expect(Rules.packaged().hazardTerms, contains('flood'));
    expect(Rules.packaged().disasterTypes, hasLength(12));
  });

  test('word cases match the contract', () {
    final rows = _loadWordCases();
    var sawFloodFloor = false;
    for (final row in rows) {
      expect(
        sameWord(row.left, row.right),
        row.match,
        reason: '${row.left} / ${row.right}',
      );
      if (row.left == 'flood' && row.right == 'floor') {
        sawFloodFloor = true;
      }
    }
    expect(
      sawFloodFloor,
      isTrue,
      reason: 'word-cases.tsv omitted flood / floor',
    );
  });

  test('stopword removal uses the rules list', () {
    final rules = Rules.parse(
      '{"relevanceThreshold":1,"minTermCoverage":0.6,"countryBoost":0.15,'
      '"hazardBoost":0.15,"poolMultiplier":5,"stopwords":["boil"],'
      '"synonyms":{},"hazardTerms":[],"disasterTypes":[]}',
    );
    expect(removeStopwords('boil water now', rules), 'water now');
  });

  test('citations keep the numbered passages', () {
    final passages = [
      passage('c1', 'One', page: 2),
      passage('c2', 'Two', page: null),
      passage('c3', 'Three', page: 4),
    ];
    final citations = [for (final item in passages) citationOf(item)];
    final used = selectCitations(
      'Boil it [3]. Then cool it [1].',
      passages,
      citations,
    );
    expect(used.map((item) => item.sourceTitle), ['One', 'Three']);
    expect(used.first.pageNumber, 2);
    expect(
      selectCitations('Boil it [7]. Or [0].', passages, citations),
      isEmpty,
    );
    expect(selectCitations(null, passages, citations), isEmpty);
    expect(
      selectCitations('Boil it for one minute.', passages, citations),
      isEmpty,
    );
  });

  group('sample bundle', () {
    late Directory temp;
    late ContentStore store;
    late Retriever retriever;
    late Rules rules;

    setUpAll(() {
      rules = Rules.parse(copyRetrievalRules());
      temp = Directory.systemTemp.createTempSync('sheltr-retrieval-');
      store = ContentStore.open(temp);
      installSample(store);
      retriever = Retriever(store, rules: rules);
    });

    tearDownAll(() {
      store.close();
      temp.deleteSync(recursive: true);
    });

    test('odd questions return refuse or passages', () {
      final paste = ('during an earthquake cover your head. ' * 200).substring(
        0,
        5000,
      );
      expect(paste.length, 5000);
      final questions = <String>[
        '',
        'how do I',
        '??? --- ...',
        '地震の備えについて',
        paste,
        'earthquake AND flood OR fire NOT "cover"',
      ];
      for (final question in questions) {
        final result = retriever.retrieve(RetrievalQuery(text: question));
        expect(
          result.kind,
          anyOf('passages', 'refuse'),
          reason: 'question length ${question.length}',
        );
      }
      expect(retriever.retrieve(RetrievalQuery(text: '')).kind, 'refuse');
      expect(
        retriever.retrieve(RetrievalQuery(text: 'how do I')).kind,
        'refuse',
      );
      expect(
        retriever.retrieve(RetrievalQuery(text: '??? --- ...')).kind,
        'refuse',
      );
    });

    test('an answer carries agency title date page and source link', () {
      final result = retriever.retrieve(
        RetrievalQuery(text: 'what should I do during an earthquake'),
      );
      expect(result.kind, 'passages');
      expect(result.passages, isNotEmpty);
      for (final item in result.passages) {
        expect(item.agency, isNotEmpty);
        expect(item.title, isNotEmpty);
        expect(item.publishedDate, isNotEmpty);
        expect(item.sourceLink, isNotEmpty);
        expect(item.page, anyOf(isNull, isA<int>()));
        final citation = citationOf(item);
        expect(citation.sourceTitle, item.title);
        expect(citation.sourceUrl, item.sourceLink);
        expect(citation.pageNumber, item.page);
      }
    });

    test('retrieval cases have no false accepts', () {
      final falseAccepts = <String>[];
      for (final row in _loadRetrievalCases().where((row) => !row.answerable)) {
        final result = retriever.retrieve(
          RetrievalQuery(text: row.question, country: row.country),
        );
        if (result.kind == 'passages') {
          falseAccepts.add(row.question);
        }
      }
      // ignore: avoid_print
      print('RETRIEVAL falseAccepts=${falseAccepts.length}');
      expect(falseAccepts, isEmpty, reason: 'false accepts: $falseAccepts');
    });

    test('retrieval cases answer and keep the expected term', () {
      final cases = _loadRetrievalCases()
          .where((row) => row.answerable)
          .toList();
      final refused = <String>[];
      final missed = <String>[];
      for (final row in cases) {
        final result = retriever.retrieve(
          RetrievalQuery(text: row.question, country: row.country),
        );
        if (result.kind != 'passages') {
          refused.add(row.question);
        } else if (!result.passages.any(
          (item) =>
              item.chunk.content.toLowerCase().contains(row.term.toLowerCase()),
        )) {
          missed.add(row.question);
        }
      }
      final answered = cases.length - refused.length;
      final found = answered - missed.length;
      // docs/evaluation.md allows 10% to miss. Refusing is the safe failure.
      // One answered row can lack its expected term and still pass.
      // ignore: avoid_print
      print(
        'RETRIEVAL answerable=${cases.length} answered=$answered '
        'expectedTermHits=$found refused=$refused missed=$missed',
      );
      expect(
        answered,
        greaterThanOrEqualTo(0.9 * cases.length),
        reason: 'refused: $refused',
      );
      expect(
        found,
        greaterThanOrEqualTo(0.9 * cases.length),
        reason: 'missed: $missed',
      );
    });
  });

  test('country and hazard boosts reorder and do not answer a refusal', () {
    final rules = Rules.parse(copyRetrievalRules());
    withStore((store) {
      store.install(_boostBundle().manifest, _boostBundle().source);
      final retriever = Retriever(store, rules: rules);

      final plain = retriever.retrieve(
        RetrievalQuery(text: 'boil water', limit: 3),
      );
      expect(plain.kind, 'passages');
      expect(plain.passages.first.document.id, 'global');

      final byCountry = retriever.retrieve(
        RetrievalQuery(text: 'boil water', country: 'NO', limit: 3),
      );
      expect(byCountry.kind, 'passages');
      expect(byCountry.passages.first.document.id, 'norway');
      expect(
        byCountry.passages.map((item) => item.document.id),
        containsAll(['global', 'norway', 'sweden']),
      );

      final byHazard = retriever.retrieve(
        RetrievalQuery(text: 'boil water', activeHazards: {'FLOOD'}, limit: 3),
      );
      expect(byHazard.passages.first.document.id, 'sweden');

      final both = retriever.retrieve(
        RetrievalQuery(
          text: 'boil water',
          country: 'NO',
          activeHazards: {'STORM', 'FLOOD'},
          limit: 3,
        ),
      );
      expect(both.passages.first.document.id, 'norway');
      expect(
        both.passages.map((item) => item.document.id),
        containsAll(['global', 'norway', 'sweden']),
      );

      final probe = Retriever(store, rules: rules, threshold: 0);
      final ranked = probe.retrieve(
        RetrievalQuery(
          text: 'boil water',
          country: 'NO',
          activeHazards: {'STORM'},
          limit: 3,
        ),
      );
      expect(ranked.kind, 'passages');
      final maxScore = maxDouble(ranked.passages.map((item) => item.score));
      final maxRank = maxDouble(ranked.passages.map((item) => item.rankScore));
      expect(maxRank, greaterThan(maxScore));
      final between = (maxScore + maxRank) / 2;
      final gated = Retriever(store, rules: rules, threshold: between);
      expect(
        gated
            .retrieve(
              RetrievalQuery(
                text: 'boil water',
                country: 'NO',
                activeHazards: {'STORM'},
                limit: 3,
              ),
            )
            .kind,
        'refuse',
      );
    });
  });

  test('rejects a non-positive limit', () {
    final rules = Rules.parse(copyRetrievalRules());
    withStore((store) {
      store.install(_boostBundle().manifest, _boostBundle().source);
      final retriever = Retriever(store, rules: rules);
      expect(
        () => retriever.retrieve(RetrievalQuery(text: 'boil water', limit: 0)),
        throwsRangeError,
      );
    });
  });
}

/// Returns the text of `contract/retrieval-rules.json`.
///
/// Fails when that text differs from [packagedRetrievalRulesJson]. The
/// library parses the constant and does not read this file.
String copyRetrievalRules() {
  final contract = findUpFile('contract/retrieval-rules.json');
  final source = contract.readAsStringSync();
  expect(
    packagedRetrievalRulesJson,
    source,
    reason:
        'packaged retrieval rules differ from contract/retrieval-rules.json',
  );
  return source;
}

File findUpFile(String relative) {
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

Directory findUpDirectory(String relative) {
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

class _WordCase {
  const _WordCase(this.left, this.right, this.match);

  final String left;
  final String right;
  final bool match;
}

List<_WordCase> _loadWordCases() {
  final rows = <_WordCase>[];
  for (final raw in findUpFile('contract/word-cases.tsv').readAsLinesSync()) {
    final line = raw.trim();
    if (line.isEmpty || line.startsWith('left\t')) {
      continue;
    }
    final parts = line.split('\t');
    if (parts.length != 3) {
      throw FormatException('bad word case: $raw');
    }
    rows.add(_WordCase(parts[0], parts[1], parts[2] == 'true'));
  }
  return rows;
}

class _RetrievalCase {
  const _RetrievalCase({
    required this.answerable,
    required this.country,
    required this.question,
    required this.term,
  });

  final bool answerable;
  final String? country;
  final String question;
  final String term;
}

List<_RetrievalCase> _loadRetrievalCases() {
  final rows = <_RetrievalCase>[];
  for (final raw in findUpFile(
    'contract/retrieval-cases.tsv',
  ).readAsLinesSync()) {
    final line = raw.trimRight();
    if (line.trim().isEmpty || line.startsWith('#')) {
      continue;
    }
    final parts = line.split('\t');
    if (parts.length < 3) {
      throw FormatException('bad retrieval case: $raw');
    }
    final country = parts[1];
    rows.add(
      _RetrievalCase(
        answerable: parts[0] == 'answerable',
        country: country.isEmpty ? null : country,
        question: parts[2],
        term: parts.length > 3 ? parts[3] : '',
      ),
    );
  }
  return rows;
}

void installSample(ContentStore store) {
  final dir = findUpDirectory('apps/mobile/assets/sample-bundle');
  final manifest = parseBundleManifest(
    File('${dir.path}/bundle.json').readAsStringSync(),
  );
  store.install(manifest, (document) {
    final bytes = File('${dir.path}/documents/${document.id}.json')
        .readAsBytesSync();
    return parseDocumentFileBytes(bytes);
  });
}

void withStore(void Function(ContentStore store) body) {
  final directory = Directory.systemTemp.createTempSync('sheltr-retrieval-');
  final store = ContentStore.open(directory);
  try {
    body(store);
  } finally {
    store.close();
    directory.deleteSync(recursive: true);
  }
}

double maxDouble(Iterable<double> values) {
  return values.reduce((left, right) => left > right ? left : right);
}

Passage passage(String id, String title, {int? page}) {
  return Passage(
    chunk: StoredChunk(
      id: id,
      documentId: 'doc-$id',
      content: 'text $id',
      pageNumber: page,
      position: 0,
    ),
    document: StoredDocument(
      id: 'doc-$id',
      title: title,
      agency: 'Agency $id',
      publishedDate: '2024-01-01',
      reviewedOn: '2026-10-05',
      expiresOn: '2099-01-01',
      licence: 'CC-BY-4.0',
      country: null,
      hazards: const [],
      sourceUri: 'https://example.org/$id',
      sourceType: 'html',
      checksum: '0' * 64,
      fileChecksum: '1' * 64,
    ),
    score: 2,
    rankScore: 2,
  );
}

class _BuiltBundle {
  const _BuiltBundle(this.manifest, this._files);

  final BundleManifest manifest;
  final Map<String, DocumentFile> _files;

  DocumentFile source(BundleDocument document) => _files[document.id]!;
}

_BuiltBundle _boostBundle() {
  final documents = <_BuiltDocument>[
    _doc(
      'global',
      'Global guide',
      null,
      ['Boil water before drinking. Boil it for one full minute.'],
      ['DROUGHT'],
    ),
    _doc(
      'norway',
      'Norway guide',
      'NO',
      ['Boil water before drinking it after the storm passes.'],
      ['STORM'],
    ),
    _doc(
      'sweden',
      'Sweden guide',
      'SE',
      ['Boil water before drinking it after the flood recedes.'],
      ['FLOOD'],
    ),
    for (var i = 1; i <= 20; i++)
      _doc(
        'filler-$i',
        'Filler $i',
        'US',
        ['Keep the kit ready and check the plan number $i.'],
        ['HEATWAVE'],
      ),
  ];
  return _BuiltBundle(
    BundleManifest(
      formatVersion: bundleFormatVersion,
      sequence: 1,
      bundleVersion: '2026-10-05T12:00:00Z',
      documents: [for (final document in documents) document.entry],
    ),
    {for (final document in documents) document.entry.id: document.file},
  );
}

class _BuiltDocument {
  const _BuiltDocument(this.entry, this.file);

  final BundleDocument entry;
  final DocumentFile file;
}

_BuiltDocument _doc(
  String id,
  String title,
  String? country,
  List<String> chunks,
  List<String> hazards,
) {
  final fileChunks = [
    for (var i = 0; i < chunks.length; i++)
      DocumentChunk(id: '$id-$i', content: chunks[i], pageNumber: i + 1),
  ];
  return _BuiltDocument(
    BundleDocument(
      id: id,
      title: title,
      agency: 'Example Agency',
      publishedDate: '2024-03-15',
      reviewedOn: '2026-11-02',
      expiresOn: '2027-11-02',
      licence: 'CC-BY-4.0',
      country: country,
      hazards: hazards,
      sourceUri: 'https://example.org/$id',
      sourceType: 'html',
      checksum: '0' * 64,
      fileChecksum: '1' * 64,
      chunks: [
        for (final chunk in fileChunks)
          BundleChunkRef(id: chunk.id, checksum: '2' * 64),
      ],
    ),
    DocumentFile(id: id, chunks: fileChunks),
  );
}
