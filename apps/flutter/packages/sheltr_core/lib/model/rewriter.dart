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

final _digits = RegExp(r'\d+');
final _letters = RegExp(r'\p{L}+', unicode: true);

/// False when [modelText] is empty, `NONE`, longer than 600 characters, has a
/// digit sequence missing from [passages], or has a content word that matches
/// no word in those passages.
bool modelTextIsSupported(String modelText, List<CitedPassage> passages) {
  final trimmed = modelText.trim();
  if (trimmed.isEmpty || trimmed == 'NONE') {
    return false;
  }
  if (trimmed.length > 600) {
    return false;
  }
  for (final match in _digits.allMatches(trimmed)) {
    final digits = match.group(0)!;
    final found = passages.any((passage) => passage.text.contains(digits));
    if (!found) {
      return false;
    }
  }
  final stopwords = Rules.packaged().stopwords;
  final passageWords = <String>[
    for (final passage in passages)
      for (final match in _letters.allMatches(passage.text))
        match.group(0)!.toLowerCase(),
  ];
  for (final match in _letters.allMatches(trimmed)) {
    final word = match.group(0)!.toLowerCase();
    if (word.length < 4 || stopwords.contains(word)) {
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
