import '../store/content_store.dart';
import 'rules.dart';
import 'stopwords.dart';

/// The user's words. FTS5 syntax in [text] is not interpreted.
class RetrievalQuery {
  const RetrievalQuery({
    required this.text,
    this.country,
    this.activeHazards = const <String>{},
    this.limit,
  });

  final String text;

  /// ISO 3166-1 alpha-2 code from settings, or null.
  final String? country;

  /// Disaster type names currently active for the user.
  final Set<String> activeHazards;

  final int? limit;
}

/// One ranked chunk. [score] is the relevance gate. [rankScore] only orders.
class Passage {
  const Passage({
    required this.chunk,
    required this.document,
    required this.score,
    required this.rankScore,
  });

  final StoredChunk chunk;
  final StoredDocument document;

  /// Keyword relevance: the FTS5 bm25 score negated, so higher is better.
  final double score;

  /// [score] times the country and hazard factors. Never used for the gate.
  final double rankScore;

  String get agency => document.agency;

  String get title => document.title;

  String get publishedDate => document.publishedDate;

  int? get page => chunk.pageNumber;

  String get sourceLink => document.sourceUri;
}

/// `passages` when a chunk clears the gate, otherwise `refuse`.
class RetrievalResult {
  RetrievalResult.passages(this.passages) : kind = 'passages';

  const RetrievalResult.refuse()
    : kind = 'refuse',
      passages = const <Passage>[];

  final String kind;
  final List<Passage> passages;
}

/// Keyword retrieval. Country and hazard boosts only reorder.
///
/// The store tokenises [RetrievalQuery.text] before it reaches SQLite. A
/// passage answers when its unboosted score reaches [Rules.relevanceThreshold]
/// and it contains at least [Rules.minTermCoverage] of the question's topical
/// words. A boost cannot turn a refusal into an answer.
class Retriever {
  Retriever(ContentStore store, {Rules? rules, double? threshold})
    : this._(store, rules ?? Rules.packaged(), threshold);

  Retriever._(this._store, this._rules, double? threshold)
    : _threshold = threshold ?? _rules.relevanceThreshold;

  final ContentStore _store;
  final Rules _rules;
  final double _threshold;

  RetrievalResult retrieve(RetrievalQuery query) {
    final passages = _rank(query);
    if (_isAnswerable(passages, query.text)) {
      return RetrievalResult.passages(passages);
    }
    // One retry with a single typo fixed, for example "eartquake".
    final corrected = correctTypos(query.text, _rules);
    if (corrected == query.text) {
      return const RetrievalResult.refuse();
    }
    final correctedPassages = _rank(
      RetrievalQuery(
        text: corrected,
        country: query.country,
        activeHazards: query.activeHazards,
        limit: query.limit,
      ),
    );
    if (_isAnswerable(correctedPassages, corrected)) {
      return RetrievalResult.passages(correctedPassages);
    }
    return const RetrievalResult.refuse();
  }

  List<Passage> _rank(RetrievalQuery query) {
    final limit = query.limit ?? 5;
    if (limit <= 0) {
      throw RangeError('limit must be positive');
    }
    final country = query.country;
    final activeHazards = query.activeHazards;
    final boosting = country != null || activeHazards.isNotEmpty;
    // A larger pool lets a boosted passage that sits just below the cut move up.
    final pool = boosting ? (limit * _rules.poolMultiplier).round() : limit;
    final hits = _store.search(
      removeStopwords(expandWithSynonyms(query.text, _rules), _rules),
      pool,
    );
    final documents = <String, StoredDocument>{};
    final passages = <Passage>[];
    for (final hit in hits) {
      var document = documents[hit.chunk.documentId];
      if (document == null) {
        final found = _store.document(hit.chunk.documentId);
        if (found == null) {
          throw StateError('chunk ${hit.chunk.id} has no document');
        }
        document = found;
        documents[document.id] = document;
      }
      final score = -hit.score;
      final factor =
          _countryFactor(document.country, country) *
          _hazardFactor(document.hazards, activeHazards);
      passages.add(
        Passage(
          chunk: hit.chunk,
          document: document,
          score: score,
          rankScore: score * factor,
        ),
      );
    }
    if (!boosting) {
      return passages;
    }
    final indexed = <(Passage, int)>[
      for (var i = 0; i < passages.length; i++) (passages[i], i),
    ];
    indexed.sort((a, b) {
      final byScore = b.$1.rankScore.compareTo(a.$1.rankScore);
      if (byScore != 0) {
        return byScore;
      }
      return a.$2.compareTo(b.$2);
    });
    final sorted = [for (final entry in indexed) entry.$1];
    if (sorted.length <= limit) {
      return sorted;
    }
    return sorted.sublist(0, limit);
  }

  /// Some passage must clear the score and contain enough topical words.
  ///
  /// No passages, or a question with no topical words, is never answerable.
  bool _isAnswerable(List<Passage> passages, String questionText) {
    final terms = contentTerms(questionText, _rules);
    if (terms.isEmpty) {
      return false;
    }
    for (final passage in passages) {
      if (passage.score >= _threshold &&
          _coverage(terms, passage.chunk.content) >= _rules.minTermCoverage) {
        return true;
      }
    }
    return false;
  }

  double _coverage(Set<String> terms, String content) {
    final present = contentTerms(content, _rules).toList();
    var covered = 0;
    for (final term in terms) {
      final canonical = synonymOf(term, _rules);
      final termHit = present.any((word) => sameWord(term, word));
      final canonicalHit =
          canonical != null && present.any((word) => sameWord(canonical, word));
      if (termHit || canonicalHit) {
        covered++;
      }
    }
    return covered / terms.length;
  }

  double _countryFactor(String? documentCountry, String? userCountry) {
    if (userCountry != null && userCountry == documentCountry) {
      return 1 + _rules.countryBoost;
    }
    return 1;
  }

  double _hazardFactor(
    List<String> documentHazards,
    Set<String> activeHazards,
  ) {
    for (final hazard in documentHazards) {
      if (activeHazards.contains(hazard)) {
        return 1 + _rules.hazardBoost;
      }
    }
    return 1;
  }
}

/// Same word up to an ending: "aftershocks" / "aftershock".
///
/// They share a prefix of at least 4 letters that covers all but at most one
/// letter of the shorter word. When both words have exactly one letter left
/// after that prefix, it is a substitution, not an ending ("flood" / "floor").
bool sameWord(String a, String b) {
  if (a == b) {
    return true;
  }
  var shared = 0;
  final max = a.length < b.length ? a.length : b.length;
  while (shared < max && a.codeUnitAt(shared) == b.codeUnitAt(shared)) {
    shared++;
  }
  if (shared < 4 || shared < max - 1) {
    return false;
  }
  return !(a.length - shared == 1 && b.length - shared == 1);
}
