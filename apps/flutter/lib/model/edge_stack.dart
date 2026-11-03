import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_edge_ai/flutter_edge_ai.dart';
import 'package:flutter_edge_ai_litertlm/flutter_edge_ai_litertlm.dart';
import 'package:sheltr_core/sheltr_core.dart';

import 'laptop_edge.dart';
import 'litert_passage_model.dart';

const _laptop = bool.fromEnvironment('SHELTR_LAPTOP');
const _modelsDir = String.fromEnvironment('SHELTR_MODELS');
const _ask = String.fromEnvironment('SHELTR_ASK');

const _embedderName = 'embeddinggemma-2-text-270m.litertlm';
const _gemmaName = 'gemma-4-E2B-it.litertlm';

/// Loads LiteRT-LM on this machine when [SHELTR_LAPTOP] is set.
///
/// Model files stay outside the repository. A missing file leaves that
/// half off: no embedder means keyword search, no Gemma means passages only.
Future<LaptopEdge?> startLaptopEdge(ContentStore store) async {
  if (!_laptop || _modelsDir.isEmpty) {
    return null;
  }
  final embedderFile = File('$_modelsDir/$_embedderName');
  if (!embedderFile.existsSync()) {
    debugPrint('Sheltr: no embedder at ${embedderFile.path}');
    return null;
  }
  if (store.documents().isEmpty) {
    await _installSample(store);
  }
  await FlutterEdgeAi.initialize(
    inferenceEngines: const [LiteRtLmEngine()],
    embeddingBackends: const [LiteRtEmbeddingBackend()],
  );
  await FlutterEdgeAi.installEmbedder()
      .modelFromFile(embedderFile.path)
      .install();
  final embedder = await FlutterEdgeAi.getActiveEmbedder(
    preferredBackend: PreferredBackend.cpu,
  );
  await _index(store, embedder);
  final gemmaFile = File('$_modelsDir/$_gemmaName');
  PassageModel? model;
  if (gemmaFile.existsSync()) {
    model = LitertPassageModel(
      verifiedPath: gemmaFile.path,
      modelType: 'gemma4',
    );
  } else {
    debugPrint('Sheltr: no Gemma file at ${gemmaFile.path}');
  }
  if (_ask.isNotEmpty) {
    await _askOnce(store, embedder, model, _ask);
  }
  return LaptopEdge(
    model: model,
    embedQuery: (question) async {
      final vector = await embedder.embed(
        EmbeddingInput.text(question),
        taskType: TaskType.retrievalQuery,
      );
      return vector;
    },
  );
}

Future<void> _askOnce(
  ContentStore store,
  EmbeddingModel embedder,
  PassageModel? model,
  String question,
) async {
  final vector = await embedder.embed(
    EmbeddingInput.text(question),
    taskType: TaskType.retrievalQuery,
  );
  final result = Retriever(store).retrieveByVector(
    vector,
    RetrievalQuery(text: question),
  );
  debugPrint(
    'Sheltr: "$question" -> ${result.kind} (${result.passages.length})',
  );
  for (final passage in result.passages.take(3)) {
    debugPrint(
      'Sheltr: ${passage.title} p${passage.page} ${passage.score.toStringAsFixed(3)}',
    );
  }
  if (model == null || result.kind != 'passages') {
    return;
  }
  final lead = await model.rewrite(question, [
    for (final passage in result.passages) CitedPassage(passage.chunk.content),
  ]);
  debugPrint('Sheltr lead: $lead');
}

Future<void> _index(ContentStore store, EmbeddingModel embedder) async {
  final chunks = store.allChunks();
  if (chunks.isEmpty || store.vectorCount() == chunks.length) {
    return;
  }
  final vectors = <String, List<double>>{};
  for (var i = 0; i < chunks.length; i++) {
    final chunk = chunks[i];
    final text = chunk.content.trim();
    if (text.isEmpty) {
      continue;
    }
    try {
      vectors[chunk.id] = await embedder.embed(
        EmbeddingInput.text(text),
        taskType: TaskType.retrievalDocument,
      );
    } on Object catch (error) {
      debugPrint('Sheltr: skipped ${chunk.id}: $error');
    }
    if (i % 25 == 0) {
      debugPrint('Sheltr: embedded $i of ${chunks.length}');
    }
  }
  if (vectors.isEmpty) {
    throw StateError('EmbeddingGemma produced no vectors');
  }
  store.replaceVectors(vectors);
  debugPrint('Sheltr: stored ${vectors.length} vectors');
}

Future<void> _installSample(ContentStore store) async {
  final parsed = parseBundleManifest(
    await rootBundle.loadString('assets/sample-bundle/bundle.json'),
  );
  final bytes = <String, ByteData>{};
  for (final document in parsed.documents) {
    bytes[document.id] = await rootBundle.load(
      'assets/sample-bundle/documents/${document.id}.json',
    );
  }
  store.install(parsed, (document) {
    final data = bytes[document.id]!;
    return parseDocumentFileBytes(
      data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
    );
  });
}
