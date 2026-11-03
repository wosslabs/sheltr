import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/dart.dart';
import 'package:flutter_edge_ai/flutter_edge_ai.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sheltr/model/litert_passage_model.dart';
import 'package:sheltr_core/sheltr_core.dart';

const _instruction =
    'Restate only the quoted passages in plain language. Use no fact that is not in the passages. If the passages do not answer the question, reply with the single word NONE.';

const _baseUrl = 'https://example.org/bundle';
const _modelRelative = 'models/passage.litertlm';
const _modelFileName = 'verified-model';

void main() {
  test('checksumMismatchLeavesPreviousModel', () async {
    final good = Uint8List.fromList(const [1, 2, 3, 4, 5]);
    final bad = Uint8List.fromList(const [9, 8, 7, 6, 5]);
    final directory = Directory.systemTemp.createTempSync('sheltr-model-');
    final store = ContentStore.open(directory);
    try {
      final first = await _signedBundle(
        sequence: 1,
        bundleVersion: '2026-11-04T00:00:00Z',
        model: _modelSpec(good),
        modelBytes: good,
      );
      final installed = await SyncEngine(clientFor: first.clientFor)
          .run(_baseUrl, first.publicKey, store);
      expect(installed, isA<SyncUpdated>());
      final modelFile = File('${directory.path}/$_modelFileName');
      expect(modelFile.existsSync(), isTrue);
      expect(modelFile.readAsBytesSync(), good);

      final second = await _signedBundle(
        sequence: 2,
        bundleVersion: '2026-11-04T01:00:00Z',
        model: _modelSpec(good),
        modelBytes: bad,
      );
      final result = await SyncEngine(clientFor: second.clientFor)
          .run(_baseUrl, second.publicKey, store);
      expect(result, isA<SyncUpdated>());
      expect((result as SyncUpdated).bundleVersion, '2026-11-04T01:00:00Z');
      expect(store.sequence(), 2);
      expect(store.bundleVersion(), '2026-11-04T01:00:00Z');
      expect(modelFile.readAsBytesSync(), good);
      expect(File('${modelFile.path}.staging').existsSync(), isFalse);
    } finally {
      store.close();
      directory.deleteSync(recursive: true);
    }
  });

  test('modelDownloadFailureDoesNotChangeContentResult', () async {
    const version = '2026-11-05T00:00:00Z';
    final content = await _signedBundle(sequence: 4, bundleVersion: version);
    final contentDir = Directory.systemTemp.createTempSync('sheltr-model-');
    final contentStore = ContentStore.open(contentDir);
    late SyncResult contentResult;
    try {
      contentResult = await SyncEngine(clientFor: content.clientFor)
          .run(_baseUrl, content.publicKey, contentStore);
    } finally {
      contentStore.close();
      contentDir.deleteSync(recursive: true);
    }

    final previous = Uint8List.fromList(const [4, 4, 4, 4]);
    final failed = await _signedBundle(
      sequence: 4,
      bundleVersion: version,
      model: _modelSpec(Uint8List.fromList(const [1, 1, 1, 1])),
      modelStatus: 404,
    );
    final directory = Directory.systemTemp.createTempSync('sheltr-model-');
    final modelFile = File('${directory.path}/$_modelFileName');
    modelFile.writeAsBytesSync(previous);
    final store = ContentStore.open(directory);
    try {
      final result = await SyncEngine(clientFor: failed.clientFor)
          .run(_baseUrl, failed.publicKey, store);
      expect(result.kind, contentResult.kind);
      expect(result, isA<SyncUpdated>());
      expect(contentResult, isA<SyncUpdated>());
      final updated = result as SyncUpdated;
      final contentUpdated = contentResult as SyncUpdated;
      expect(updated.bundleVersion, contentUpdated.bundleVersion);
      expect(updated.documentsDownloaded, contentUpdated.documentsDownloaded);
      expect(store.bundleVersion(), version);
      expect(modelFile.readAsBytesSync(), previous);
      expect(
        failed.requested.any((url) => url.endsWith('/$_modelRelative')),
        isTrue,
      );
    } finally {
      store.close();
      directory.deleteSync(recursive: true);
    }
  });

  test('unknownModelTypeStaysOff', () async {
    final fake = _FakeEngine();
    final model = LitertPassageModel(
      verifiedPath: '/tmp/verified.litertlm',
      modelType: 'deepSeek',
      engine: fake,
    );
    expect(
      await model.rewrite('Where is water?', [CitedPassage('Boil water.')]),
      'NONE',
    );
    expect(fake.initializeCount, 0);
    expect(fake.installedType, isNull);

    expect(modelTypeFromManifest('gemma4'), ModelType.gemma4);
    expect(modelTypeFromManifest('gemmaIt'), ModelType.gemmaIt);
    expect(modelTypeFromManifest('qwen'), ModelType.qwen);
    expect(modelTypeFromManifest('qwen3'), ModelType.qwen3);
    expect(modelTypeFromManifest('general'), ModelType.general);
    expect(modelTypeFromManifest('deepSeek'), isNull);
    expect(modelTypeFromManifest('gemma'), isNull);
    expect(modelTypeFromManifest(''), isNull);
  });

  test('adapterSendsPassagesAndNoTools', () async {
    final fake = _FakeEngine(reply: 'Boil water.');
    final model = LitertPassageModel(
      verifiedPath: '/verified/model.litertlm',
      modelType: 'gemmaIt',
      engine: fake,
    );
    final text = await model.rewrite('How do I make water safe?', [
      CitedPassage('Boil water for one minute.'),
      CitedPassage('Store drinking water.'),
    ]);
    expect(text, 'Boil water.');
    expect(fake.initializeCount, 1);
    expect(fake.installedType, ModelType.gemmaIt);
    expect(fake.installedFileType, ModelFileType.litertlm);
    expect(fake.installedPath, '/verified/model.litertlm');
    expect(fake.maxTokens, 256);
    expect(fake.backend, PreferredBackend.cpu);
    expect(fake.systemInstruction, _instruction);
    expect(fake.enableThinking, isFalse);
    expect(fake.tools, isNull);
    expect(fake.images, isFalse);
    expect(fake.audio, isFalse);
    expect(fake.isUser, isTrue);
    expect(fake.userText, contains('How do I make water safe?'));
    expect(fake.userText, contains('"Boil water for one minute."'));
    expect(fake.userText, contains('"Store drinking water."'));
    expect(fake.sessionClosed, isTrue);
    expect(fake.modelClosed, isTrue);

    final failing = _FakeEngine(error: StateError('stopped'));
    final again = LitertPassageModel(
      verifiedPath: '/verified/model.litertlm',
      modelType: 'general',
      engine: failing,
    );
    await expectLater(
      again.rewrite('question', [CitedPassage('Boil water.')]),
      throwsA(isA<StateError>()),
    );
    expect(failing.sessionOpened, isTrue);
    expect(failing.sessionClosed, isTrue);
    expect(failing.modelClosed, isTrue);
  });

  test('lib does not call fromNetwork or huggingface', () {
    final lib = Directory('lib');
    final hits = <String>[];
    for (final entity in lib.listSync(recursive: true, followLinks: false)) {
      if (entity is! File || !entity.path.endsWith('.dart')) {
        continue;
      }
      final text = entity.readAsStringSync();
      if (text.contains('fromNetwork') ||
          text.toLowerCase().contains('huggingface')) {
        hits.add(entity.path);
      }
    }
    expect(hits, isEmpty, reason: 'forbidden call in: ${hits.join(', ')}');

    final adapter = File('lib/model/litert_passage_model.dart')
        .readAsStringSync();
    expect(adapter, contains('inferenceEngines: const [LiteRtLmEngine()]'));
    expect(adapter, contains('fileType: ModelFileType.litertlm'));
    expect(adapter, contains('.fromFile('));
    expect(adapter, contains('maxTokens: 256'));
    expect(adapter, contains('preferredBackend: PreferredBackend.cpu'));
    expect(adapter, contains('enableThinking: false'));
    expect(adapter, contains('finally'));
    expect(adapter, isNot(contains('tools:')));
  });
}

Map<String, Object?> _modelSpec(Uint8List bytes) {
  return {
    'file': _modelRelative,
    'sha256': sha256Hex(bytes),
    'byteSize': bytes.length,
    'modelType': 'gemma4',
  };
}

Future<_HostedBundle> _signedBundle({
  required int sequence,
  required String bundleVersion,
  Map<String, Object?>? model,
  Uint8List? modelBytes,
  int modelStatus = 200,
}) async {
  final fixture = _fixtureDirectory('valid-signed');
  final manifest = jsonDecode(
    File('${fixture.path}/bundle.json').readAsStringSync(),
  ) as Map<String, Object?>;
  manifest['sequence'] = sequence;
  manifest['bundleVersion'] = bundleVersion;
  manifest.remove('signedBy');
  if (model == null) {
    manifest.remove('model');
  } else {
    manifest['model'] = model;
  }
  final manifestBytes = Uint8List.fromList(utf8.encode(jsonEncode(manifest)));
  final algorithm = DartEd25519();
  final keyPair = await algorithm.newKeyPair();
  final signature = await algorithm.sign(manifestBytes, keyPair: keyPair);
  final publicKey = await keyPair.extractPublicKey();
  final documents = <String, Uint8List>{};
  for (final item in manifest['documents']! as List<Object?>) {
    final id = (item! as Map)['id'] as String;
    documents[id] = File('${fixture.path}/documents/$id.json')
        .readAsBytesSync();
  }
  return _HostedBundle(
    manifestBytes: manifestBytes,
    signature: Uint8List.fromList(signature.bytes),
    publicKey: Uint8List.fromList(publicKey.bytes),
    documents: documents,
    modelBytes: modelBytes,
    modelStatus: modelStatus,
  );
}

class _HostedBundle {
  _HostedBundle({
    required this.manifestBytes,
    required this.signature,
    required this.publicKey,
    required this.documents,
    required this.modelBytes,
    required this.modelStatus,
  });

  final Uint8List manifestBytes;
  final Uint8List signature;
  final Uint8List publicKey;
  final Map<String, Uint8List> documents;
  final Uint8List? modelBytes;
  final int modelStatus;
  final requested = <String>[];

  SyncClient clientFor(String baseUrl) {
    return FetchSyncClient(
      baseUrl,
      fetch: (url) async {
        requested.add(url);
        final base = baseUrl.replaceAll(RegExp(r'/+$'), '');
        final prefix = '$base/';
        if (!url.startsWith(prefix)) {
          return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
        }
        final relative = url.substring(prefix.length);
        if (relative == bundleManifestFile) {
          return SyncResponse(ok: true, status: 200, bytes: manifestBytes);
        }
        if (relative == bundleSignatureFile) {
          return SyncResponse(ok: true, status: 200, bytes: signature);
        }
        const documentsPrefix = '$bundleDocumentsDir/';
        if (relative.startsWith(documentsPrefix) &&
            relative.endsWith('.json')) {
          final id = relative.substring(
            documentsPrefix.length,
            relative.length - '.json'.length,
          );
          final bytes = documents[id];
          if (bytes == null) {
            return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
          }
          return SyncResponse(ok: true, status: 200, bytes: bytes);
        }
        if (relative == _modelRelative) {
          if (modelStatus != 200 || modelBytes == null) {
            return SyncResponse(
              ok: false,
              status: modelStatus,
              bytes: Uint8List(0),
            );
          }
          return SyncResponse(ok: true, status: 200, bytes: modelBytes!);
        }
        return SyncResponse(ok: false, status: 404, bytes: Uint8List(0));
      },
    );
  }
}

Directory _fixtureDirectory(String name) {
  var dir = Directory.current;
  while (true) {
    final candidate = Directory('${dir.path}/contract/fixtures/$name');
    if (candidate.existsSync()) {
      return candidate;
    }
    final parent = dir.parent;
    if (parent.path == dir.path) {
      throw StateError(
        'contract/fixtures/$name not found from ${Directory.current.path}',
      );
    }
    dir = parent;
  }
}

class _FakeEngine implements LitertEngine {
  _FakeEngine({this.reply = '', this.error});

  final String reply;
  final Object? error;
  var initializeCount = 0;
  ModelType? installedType;
  ModelFileType? installedFileType;
  String? installedPath;
  int? maxTokens;
  PreferredBackend? backend;
  String? systemInstruction;
  bool? enableThinking;
  List<Object?>? tools;
  bool? images;
  bool? audio;
  String? userText;
  bool? isUser;
  var sessionOpened = false;
  var sessionClosed = false;
  var modelClosed = false;

  @override
  Future<void> initialize() async {
    initializeCount += 1;
  }

  @override
  Future<void> installFromFile({
    required ModelType modelType,
    required ModelFileType fileType,
    required String path,
  }) async {
    installedType = modelType;
    installedFileType = fileType;
    installedPath = path;
  }

  @override
  Future<LitertLoadedModel> openModel({
    required int maxTokens,
    required PreferredBackend backend,
  }) async {
    this.maxTokens = maxTokens;
    this.backend = backend;
    return _FakeLoaded(this);
  }
}

class _FakeLoaded implements LitertLoadedModel {
  _FakeLoaded(this.engine);

  final _FakeEngine engine;

  @override
  Future<LitertSession> createSession({
    required String systemInstruction,
    required bool enableThinking,
    List<Object?>? tools,
    bool? images,
    bool? audio,
  }) async {
    engine.systemInstruction = systemInstruction;
    engine.enableThinking = enableThinking;
    engine.tools = tools;
    engine.images = images;
    engine.audio = audio;
    engine.sessionOpened = true;
    return _FakeSession(engine);
  }

  @override
  Future<void> close() async {
    engine.modelClosed = true;
  }
}

class _FakeSession implements LitertSession {
  _FakeSession(this.engine);

  final _FakeEngine engine;

  @override
  Future<void> addUserText(String text, {required bool isUser}) async {
    engine.userText = text;
    engine.isUser = isUser;
  }

  @override
  Future<String> response() async {
    final error = engine.error;
    if (error != null) {
      throw error;
    }
    return engine.reply;
  }

  @override
  Future<void> close() async {
    engine.sessionClosed = true;
  }
}
