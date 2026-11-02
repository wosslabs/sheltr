import 'rules.dart';

/// A word is a maximal run of letters or digits.
final _word = RegExp(r'[\p{L}\p{N}]+', unicode: true);

List<String> _wordsOf(String text) {
  return [for (final match in _word.allMatches(text)) match.group(0)!];
}

/// Drops stopwords from [rules], keeping the remaining words in order.
///
/// An empty result is a query with no words. The store then matches nothing.
String removeStopwords(String text, Rules rules) {
  return [
    for (final word in _wordsOf(text))
      if (!rules.stopwords.contains(word.toLowerCase())) word,
  ].join(' ');
}

/// Distinct lower-case topical words of [text].
Set<String> contentTerms(String text, Rules rules) {
  final terms = <String>{};
  for (final word in _wordsOf(text)) {
    final lower = word.toLowerCase();
    if (!rules.stopwords.contains(lower)) {
      terms.add(lower);
    }
  }
  return terms;
}

/// The canonical hazard term for a colloquial word, when [rules] names one.
String? synonymOf(String word, Rules rules) {
  if (!rules.synonyms.containsKey(word)) {
    return null;
  }
  return rules.synonyms[word];
}

/// Appends the canonical term for each synonym in [text].
///
/// "quake" also searches "earthquake". The original text is kept. A canonical
/// term already present is not appended again.
String expandWithSynonyms(String text, Rules rules) {
  final words = [for (final word in _wordsOf(text)) word.toLowerCase()];
  final present = words.toSet();
  final expansions = <String>{};
  for (final word in words) {
    final canonical = synonymOf(word, rules);
    if (canonical != null && !present.contains(canonical)) {
      expansions.add(canonical);
    }
  }
  if (expansions.isEmpty) {
    return text;
  }
  return '$text ${expansions.join(' ')}';
}

/// Shortest word eligible for typo correction. Below this, ordinary English
/// words sit one edit from a short hazard term ("food" / "flood").
const _minTypoLength = 7;

/// One edit only. At two, "water" would be rewritten as "winter".
const _maxTypoDistance = 1;

/// Replaces a word that is one edit from exactly one hazard term.
///
/// "eartquake" searches "earthquake". Vocabulary words, synonyms, short words,
/// and ambiguous edits are left as written. "drought" is not a target: it is
/// one edit from "brought".
String correctTypos(String text, Rules rules) {
  final targets = [
    for (final term in rules.hazardTerms)
      if (term.length >= _minTypoLength && term != 'drought') term,
  ];
  return text.replaceAllMapped(_word, (match) {
    return _correctWord(match.group(0)!, rules, targets);
  });
}

String _correctWord(String word, Rules rules, List<String> targets) {
  final lower = word.toLowerCase();
  if (word.length < _minTypoLength) {
    return word;
  }
  if (rules.hazardTerms.contains(lower) || rules.synonyms.containsKey(lower)) {
    return word;
  }
  if (targets.isEmpty) {
    return word;
  }

  var minimum = _levenshtein(lower, targets.first);
  final nearest = <String>[targets.first];
  for (final term in targets.skip(1)) {
    final distance = _levenshtein(lower, term);
    if (distance < minimum) {
      minimum = distance;
      nearest
        ..clear()
        ..add(term);
    } else if (distance == minimum) {
      nearest.add(term);
    }
  }
  if (minimum <= _maxTypoDistance && nearest.length == 1) {
    return nearest.single;
  }
  return word;
}

/// Levenshtein distance, one rolling row.
int _levenshtein(String a, String b) {
  if (a == b) {
    return 0;
  }
  if (a.isEmpty) {
    return b.length;
  }
  if (b.isEmpty) {
    return a.length;
  }

  var previous = List<int>.generate(b.length + 1, (index) => index);
  var current = List<int>.filled(b.length + 1, 0);
  for (var i = 1; i <= a.length; i++) {
    current[0] = i;
    for (var j = 1; j <= b.length; j++) {
      final substitutionCost = a.codeUnitAt(i - 1) == b.codeUnitAt(j - 1)
          ? 0
          : 1;
      final insert = current[j - 1] + 1;
      final delete = previous[j] + 1;
      final substitute = previous[j - 1] + substitutionCost;
      var best = insert;
      if (delete < best) {
        best = delete;
      }
      if (substitute < best) {
        best = substitute;
      }
      current[j] = best;
    }
    final swap = previous;
    previous = current;
    current = swap;
  }
  return previous[b.length];
}
