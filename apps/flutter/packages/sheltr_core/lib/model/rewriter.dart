import '../retrieval/retriever.dart';
import '../retrieval/rules.dart';

/// A quoted passage the model may restate. The support check reads [text].
class CitedPassage {
  const CitedPassage(this.text);

  final String text;
}

/// Rewrites cited passages into a short lead. A refusal does not call it.
abstract class PassageModel {
  Future<String> rewrite(String question, List<CitedPassage> passages);
}

/// What the answer screen shows above, or instead of, a generated lead.
class AnswerPresentation {
  const AnswerPresentation({required this.lead, required this.passages});

  /// Model text that passed the support check, or null when the lead is hidden.
  final String? lead;

  /// The retrieval passages. Hiding [lead] does not remove them.
  final List<Passage> passages;
}

final _asciiDigitRun = RegExp(r'[0-9]+');
final _decimalDigit = RegExp(r'\p{Nd}', unicode: true);
final _letters = RegExp(r'\p{L}+', unicode: true);

/// Retrieval stopwords the support check still compares with [sameWord].
///
/// These change when or how much a passage tells someone to do. The retrieval
/// list skips them. This check does not.
const _checkedStopwords = {
  'before',
  'after',
  'during',
  'until',
  'under',
  'over',
  'more',
  'once',
  'only',
};

bool _hasNonAsciiDigit(String text) {
  for (final match in _decimalDigit.allMatches(text)) {
    final rune = match.group(0)!.runes.single;
    if (rune < 0x30 || rune > 0x39) {
      return true;
    }
  }
  return false;
}

/// False when [modelText] is empty, `NONE`, longer than 600 characters, has a
/// Unicode digit that is not ASCII 0-9, has a maximal ASCII digit run that is
/// not equal to a maximal ASCII digit run in [passages], or has a word of four
/// or more letters that matches no word in those passages.
///
/// The skip list is the retrieval stopwords without before, after, during,
/// until, under, over, more, once, and only. A word on that shorter list is
/// skipped. Those nine words, including the four-letter ones, are compared
/// with [sameWord].
bool modelTextIsSupported(String modelText, List<CitedPassage> passages) {
  final trimmed = modelText.trim();
  if (trimmed.isEmpty || trimmed == 'NONE') {
    return false;
  }
  if (trimmed.length > 600) {
    return false;
  }
  if (_hasNonAsciiDigit(trimmed)) {
    return false;
  }
  final passageDigits = <String>{
    for (final passage in passages)
      for (final match in _asciiDigitRun.allMatches(passage.text))
        match.group(0)!,
  };
  for (final match in _asciiDigitRun.allMatches(trimmed)) {
    if (!passageDigits.contains(match.group(0)!)) {
      return false;
    }
  }
  final skipWords = Rules.packaged().stopwords.difference(_checkedStopwords);
  final passageWords = <String>[
    for (final passage in passages)
      for (final match in _letters.allMatches(passage.text))
        match.group(0)!.toLowerCase(),
  ];
  for (final match in _letters.allMatches(trimmed)) {
    final word = match.group(0)!.toLowerCase();
    if (word.length < 4 || skipWords.contains(word)) {
      continue;
    }
    final supported = passageWords.any(
      (candidate) => sameWord(word, candidate),
    );
    if (!supported) {
      return false;
    }
  }
  return true;
}

/// Hides [modelText] when the model is off, retrieval refused, or the support
/// check fails. The passages stay the retrieval passages.
AnswerPresentation present({
  required RetrievalResult retrieval,
  String? modelText,
  required bool modelEnabled,
}) {
  if (retrieval.kind != 'passages' || !modelEnabled || modelText == null) {
    return AnswerPresentation(lead: null, passages: retrieval.passages);
  }
  final cited = [
    for (final passage in retrieval.passages)
      CitedPassage(passage.chunk.content),
  ];
  if (!modelTextIsSupported(modelText, cited)) {
    return AnswerPresentation(lead: null, passages: retrieval.passages);
  }
  return AnswerPresentation(lead: modelText, passages: retrieval.passages);
}

/// Calls [model] only when retrieval returned passages and the model is enabled.
Future<AnswerPresentation> presentWithModel({
  required PassageModel model,
  required String question,
  required RetrievalResult retrieval,
  required bool modelEnabled,
}) async {
  if (retrieval.kind != 'passages' || !modelEnabled) {
    return present(
      retrieval: retrieval,
      modelText: null,
      modelEnabled: modelEnabled,
    );
  }
  final cited = [
    for (final passage in retrieval.passages)
      CitedPassage(passage.chunk.content),
  ];
  final modelText = await model.rewrite(question, cited);
  return present(
    retrieval: retrieval,
    modelText: modelText,
    modelEnabled: modelEnabled,
  );
}
