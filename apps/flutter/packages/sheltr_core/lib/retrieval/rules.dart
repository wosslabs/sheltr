import 'dart:convert';

import 'retrieval_rules.g.dart';

/// Relevance threshold, coverage, boosts, and the word lists they use.
///
/// [packaged] parses the copy of `contract/retrieval-rules.json` compiled
/// into this library. Every numeric field is read as [num] and stored as
/// [double], because the contract writes `relevanceThreshold` as the JSON
/// integer 1.
class Rules {
  const Rules({
    required this.relevanceThreshold,
    required this.minTermCoverage,
    required this.countryBoost,
    required this.hazardBoost,
    required this.poolMultiplier,
    required this.stopwords,
    required this.synonyms,
    required this.hazardTerms,
    required this.disasterTypes,
  });

  final double relevanceThreshold;
  final double minTermCoverage;
  final double countryBoost;
  final double hazardBoost;
  final double poolMultiplier;
  final Set<String> stopwords;
  final Map<String, String> synonyms;
  final Set<String> hazardTerms;
  final List<String> disasterTypes;

  /// Reads [Rules] from the JSON text of `retrieval-rules.json`.
  factory Rules.parse(String text) {
    final decoded = jsonDecode(text);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('retrieval rules must be a JSON object');
    }
    return Rules.fromJson(decoded);
  }

  factory Rules.fromJson(Map<String, dynamic> json) {
    return Rules(
      relevanceThreshold: _doubleField(json, 'relevanceThreshold'),
      minTermCoverage: _doubleField(json, 'minTermCoverage'),
      countryBoost: _doubleField(json, 'countryBoost'),
      hazardBoost: _doubleField(json, 'hazardBoost'),
      poolMultiplier: _doubleField(json, 'poolMultiplier'),
      stopwords: Set<String>.unmodifiable(_stringList(json, 'stopwords')),
      synonyms: Map<String, String>.unmodifiable(_stringMap(json, 'synonyms')),
      hazardTerms: Set<String>.unmodifiable(_stringList(json, 'hazardTerms')),
      disasterTypes: List<String>.unmodifiable(
        _stringList(json, 'disasterTypes'),
      ),
    );
  }

  /// The contract rules compiled into this library.
  static Rules packaged() {
    return Rules.parse(packagedRetrievalRulesJson);
  }
}

double _doubleField(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! num) {
    throw FormatException('$key must be a number');
  }
  return value.toDouble();
}

List<String> _stringList(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! List) {
    throw FormatException('$key must be an array');
  }
  return [for (final item in value) item as String];
}

Map<String, String> _stringMap(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! Map) {
    throw FormatException('$key must be an object');
  }
  final mapped = <String, String>{};
  for (final entry in value.entries) {
    final name = entry.key;
    final target = entry.value;
    if (name is! String || target is! String) {
      throw FormatException('$key must map strings to strings');
    }
    mapped[name] = target;
  }
  return mapped;
}
