import 'dart:io';

import 'package:sheltr_core/bundle/format.dart';
import 'package:sheltr_core/retrieval/retriever.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:test/test.dart';

void main() {
  test('cosineRejectsAZeroVectorAndKeepsTheCloserChunk', () {
    final directory = Directory.systemTemp.createTempSync('sheltr-vectors-');
    final store = ContentStore.open(directory.path);
    try {
      store.install(_manifest(), (document) => _file());
      expect(store.vectorCount(), 0);
      expect(
        () => store.replaceVectors({
          'doc-a-0': const [1],
          'doc-a-1': const [0, 1],
        }),
        throwsArgumentError,
      );
      expect(store.vectorCount(), 0);

      store.replaceVectors({
        'doc-a-0': const [1, 0],
        'doc-a-1': const [0, 1],
      });
      expect(store.vectorCount(), 2);

      final water = Retriever(store).retrieveByVector(
        const [1, 0],
        const RetrievalQuery(text: 'save water'),
        minScore: 0.9,
      );
      expect(water.kind, 'passages');
      expect(water.passages.single.chunk.id, 'doc-a-0');
      expect(water.passages.single.chunk.content, contains('water'));

      final offTopic = Retriever(store).retrieveByVector(
        const [0, 0],
        const RetrievalQuery(text: 'flat tire'),
        minScore: vectorRelevanceFloor,
      );
      expect(offTopic.kind, 'refuse');

      final reopened = ContentStore.open(directory.path);
      try {
        expect(reopened.vectorCount(), 2);
        final again = Retriever(reopened).retrieveByVector(
          const [0, 1],
          const RetrievalQuery(text: 'tires'),
          minScore: 0.9,
        );
        expect(again.passages.single.chunk.id, 'doc-a-1');
      } finally {
        reopened.close();
      }
    } finally {
      store.close();
      directory.deleteSync(recursive: true);
    }
  });
}

BundleManifest _manifest() {
  return BundleManifest(
    formatVersion: bundleFormatVersion,
    sequence: 1,
    bundleVersion: '2026-11-03T00:00:00Z',
    documents: [
      BundleDocument(
        id: 'doc-a',
        title: 'Water and tires',
        agency: 'Example Agency',
        publishedDate: '2024-03-15',
        reviewedOn: '2026-11-03',
        expiresOn: '2027-11-03',
        licence: 'CC-BY-4.0',
        country: null,
        hazards: const ['DROUGHT'],
        sourceUri: 'https://example.org/doc-a',
        sourceType: 'html',
        checksum: 'abc',
        fileChecksum: 'def',
        chunks: const [
          BundleChunkRef(id: 'doc-a-0', checksum: 'c0'),
          BundleChunkRef(id: 'doc-a-1', checksum: 'c1'),
        ],
      ),
    ],
  );
}

DocumentFile _file() {
  return const DocumentFile(
    id: 'doc-a',
    chunks: [
      DocumentChunk(
        id: 'doc-a-0',
        content: 'Store drinking water for three days.',
        pageNumber: null,
      ),
      DocumentChunk(
        id: 'doc-a-1',
        content: 'Check the spare tire before a long trip.',
        pageNumber: null,
      ),
    ],
  );
}
