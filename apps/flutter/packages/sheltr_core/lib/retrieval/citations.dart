import 'retriever.dart';

/// A source shown with an answer.
class Citation {
  const Citation({
    required this.documentId,
    required this.chunkId,
    required this.sourceTitle,
    required this.sourceUrl,
    required this.pageNumber,
  });

  final String documentId;
  final String chunkId;
  final String sourceTitle;
  final String sourceUrl;
  final int? pageNumber;
}

Citation citationOf(Passage passage) {
  return Citation(
    documentId: passage.document.id,
    chunkId: passage.chunk.id,
    sourceTitle: passage.title,
    sourceUrl: passage.sourceLink,
    pageNumber: passage.page,
  );
}

final _marker = RegExp(r'\[([0-9]{1,3})\]');

/// Numbered passages an answer cites, written as [1], [2], and so on.
///
/// Markers are 1-based and one to three digits. Numbers outside the passage
/// list are ignored. Each cited chunk is returned once, in [citations] order.
List<Citation> selectCitations(
  String? answer,
  List<Passage> passages,
  List<Citation> citations,
) {
  return selectByChunkIds(answer, [
    for (final passage in passages) passage.chunk.id,
  ], citations);
}

/// The same rule over chunk ids in prompt order.
List<Citation> selectByChunkIds(
  String? answer,
  List<String> passageChunkIds,
  List<Citation> citations,
) {
  final cited = <String>{};
  for (final match in _marker.allMatches(answer ?? '')) {
    final number = int.parse(match.group(1)!);
    if (number >= 1 && number <= passageChunkIds.length) {
      cited.add(passageChunkIds[number - 1]);
    }
  }
  return [
    for (final citation in citations)
      if (cited.contains(citation.chunkId)) citation,
  ];
}
