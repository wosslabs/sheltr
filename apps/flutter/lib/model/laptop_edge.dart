import 'package:sheltr_core/sheltr_core.dart';

/// Local EmbeddingGemma index plus the optional Gemma passage model.
class LaptopEdge {
  const LaptopEdge({required this.embedQuery, this.model});

  final Future<List<double>> Function(String question) embedQuery;
  final PassageModel? model;
}
