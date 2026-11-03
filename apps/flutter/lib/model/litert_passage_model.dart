import 'package:flutter_edge_ai/flutter_edge_ai.dart';
import 'package:flutter_edge_ai_litertlm/flutter_edge_ai_litertlm.dart';
import 'package:sheltr_core/sheltr_core.dart';

/// System instruction for every passage session.
const String litertSystemInstruction =
    'Restate only the quoted passages in plain language. Use no fact that is not in the passages. If the passages do not answer the question, reply with the single word NONE.';

/// Maps a manifest `modelType` onto the engine's [ModelType].
///
/// Any string other than the five publication types returns null, and the
/// model stays off.
ModelType? modelTypeFromManifest(String modelType) {
  return switch (modelType) {
    'gemma4' => ModelType.gemma4,
    'gemmaIt' => ModelType.gemmaIt,
    'qwen' => ModelType.qwen,
    'qwen3' => ModelType.qwen3,
    'general' => ModelType.general,
    _ => null,
  };
}

/// The slice of the LiteRT-LM engine the passage adapter uses.
///
/// Tests pass a fake. The real engine is [FlutterLitertEngine].
abstract class LitertEngine {
  Future<void> initialize();

  Future<void> installFromFile({
    required ModelType modelType,
    required ModelFileType fileType,
    required String path,
  });

  Future<LitertLoadedModel> openModel({
    required int maxTokens,
    required PreferredBackend backend,
  });
}

abstract class LitertLoadedModel {
  Future<LitertSession> createSession({
    required String systemInstruction,
    required bool enableThinking,
    List<Object?>? tools,
    bool? images,
    bool? audio,
    int? maxOutputTokens,
  });

  Future<void> close();
}

abstract class LitertSession {
  Future<void> addUserText(String text, {required bool isUser});

  Future<String> response();

  Future<void> close();
}

/// Restates cited passages with a local `.litertlm` file on the CPU.
///
/// [verifiedPath] has already passed the SHA-256 in the signed manifest.
/// [modelType] is the manifest string. An unknown type does not start the
/// engine and [rewrite] returns `NONE`.
class LitertPassageModel implements PassageModel {
  LitertPassageModel({
    required this.verifiedPath,
    required this.modelType,
    LitertEngine? engine,
  }) : _engine = engine ?? FlutterLitertEngine();

  final String verifiedPath;
  final String modelType;
  final LitertEngine _engine;

  @override
  Future<String> rewrite(String question, List<CitedPassage> passages) async {
    final mapped = modelTypeFromManifest(modelType);
    if (mapped == null) {
      return 'NONE';
    }
    await _engine.initialize();
    await _engine.installFromFile(
      modelType: mapped,
      fileType: ModelFileType.litertlm,
      path: verifiedPath,
    );
    final loaded = await _engine.openModel(
      maxTokens: 2048,
      backend: PreferredBackend.cpu,
    );
    LitertSession? session;
    try {
      session = await loaded.createSession(
        systemInstruction: litertSystemInstruction,
        enableThinking: false,
        images: false,
        audio: false,
        maxOutputTokens: 256,
      );
      await session.addUserText(_prompt(question, passages), isUser: true);
      return await session.response();
    } finally {
      try {
        await session?.close();
      } finally {
        await loaded.close();
      }
    }
  }
}

String _prompt(String question, List<CitedPassage> passages) {
  final buffer = StringBuffer('Question: $question\n');
  for (final passage in passages) {
    buffer.writeln('"${passage.text}"');
  }
  return buffer.toString();
}

/// Calls LiteRT-LM. Not constructed by `flutter test`.
class FlutterLitertEngine implements LitertEngine {
  @override
  Future<void> initialize() {
    return FlutterEdgeAi.initialize(inferenceEngines: const [LiteRtLmEngine()]);
  }

  @override
  Future<void> installFromFile({
    required ModelType modelType,
    required ModelFileType fileType,
    required String path,
  }) async {
    await FlutterEdgeAi.installModel(
      modelType: modelType,
      fileType: fileType,
    ).fromFile(path).install();
  }

  @override
  Future<LitertLoadedModel> openModel({
    required int maxTokens,
    required PreferredBackend backend,
  }) async {
    if (backend != PreferredBackend.cpu) {
      throw StateError('The passage model runs on the CPU only.');
    }
    final model = await FlutterEdgeAi.getActiveModel(
      maxTokens: maxTokens,
      preferredBackend: PreferredBackend.cpu,
    );
    return _EdgeLoadedModel(model);
  }
}

class _EdgeLoadedModel implements LitertLoadedModel {
  _EdgeLoadedModel(this._model);

  final InferenceModel _model;

  @override
  Future<LitertSession> createSession({
    required String systemInstruction,
    required bool enableThinking,
    List<Object?>? tools,
    bool? images,
    bool? audio,
    int? maxOutputTokens,
  }) async {
    if (tools != null || images == true || audio == true) {
      throw StateError('The passage model takes no tools, images, or audio.');
    }
    final session = await _model.createSession(
      systemInstruction: systemInstruction,
      enableThinking: enableThinking,
      enableVisionModality: false,
      enableAudioModality: false,
      maxOutputTokens: maxOutputTokens,
    );
    return _EdgeSession(session);
  }

  @override
  Future<void> close() => _model.close();
}

class _EdgeSession implements LitertSession {
  _EdgeSession(this._session);

  final InferenceModelSession _session;

  @override
  Future<void> addUserText(String text, {required bool isUser}) {
    return _session.addQueryChunk(Message.text(text: text, isUser: isUser));
  }

  @override
  Future<String> response() => _session.getResponse();

  @override
  Future<void> close() => _session.close();
}
