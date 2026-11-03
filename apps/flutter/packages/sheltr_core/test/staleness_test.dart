import 'dart:io';

import 'package:sheltr_core/bundle/format.dart';
import 'package:sheltr_core/bundle/parser.dart';
import 'package:sheltr_core/retrieval/retriever.dart';
import 'package:sheltr_core/store/content_store.dart';
import 'package:sheltr_core/store/staleness.dart';
import 'package:test/test.dart';

void main() {
  final now = DateTime.utc(2026, 11, 2, 12);

  test('expired when expiresOn is before the device date', () {
    final freshness = assessFreshness(
      now: now,
      bundleVersion: '2026-10-01T00:00:00Z',
      lastSyncEpochSeconds: _epoch(now),
      expiresOn: const ['2026-11-01'],
    );
    expect(freshness.clockUnsure, isFalse);
    expect(freshness.staleness, Staleness.expired);
    expect(freshness.expiringSoonReason, isNull);
  });

  test('expiringSoon inside the 30 day window, including the 30th day', () {
    final today = assessFreshness(
      now: now,
      bundleVersion: '2026-10-01T00:00:00Z',
      lastSyncEpochSeconds: _epoch(now),
      expiresOn: const ['2026-11-02'],
    );
    expect(today.staleness, Staleness.expiringSoon);
    expect(today.expiringSoonReason, ExpiringSoonReason.documentDate);
    expect(
      assessFreshness(
        now: now,
        bundleVersion: '2026-10-01T00:00:00Z',
        lastSyncEpochSeconds: _epoch(now),
        expiresOn: const ['2026-12-02'],
      ).staleness,
      Staleness.expiringSoon,
    );
    expect(
      assessFreshness(
        now: now,
        bundleVersion: '2026-10-01T00:00:00Z',
        lastSyncEpochSeconds: _epoch(now),
        expiresOn: const ['2026-12-03'],
      ).staleness,
      Staleness.fresh,
    );
  });

  test('expiringSoon when the last successful sync is older than 90 days', () {
    final exactly = now.subtract(const Duration(days: 90));
    expect(
      assessFreshness(
        now: now,
        bundleVersion: '2026-01-01T00:00:00Z',
        lastSyncEpochSeconds: _epoch(exactly),
        expiresOn: const ['2027-11-02'],
      ).staleness,
      Staleness.fresh,
    );

    final older = exactly.subtract(const Duration(seconds: 1));
    final freshness = assessFreshness(
      now: now,
      bundleVersion: '2026-01-01T00:00:00Z',
      lastSyncEpochSeconds: _epoch(older),
      expiresOn: const ['2027-11-02'],
    );
    expect(freshness.staleness, Staleness.expiringSoon);
    expect(freshness.expiringSoonReason, ExpiringSoonReason.lastSync);
  });

  test('expiringSoon records both a near expiry and an old sync', () {
    final older = now.subtract(const Duration(days: 91));
    final freshness = assessFreshness(
      now: now,
      bundleVersion: '2026-01-01T00:00:00Z',
      lastSyncEpochSeconds: _epoch(older),
      expiresOn: const ['2026-11-22'],
    );
    expect(freshness.staleness, Staleness.expiringSoon);
    expect(freshness.expiringSoonReason, ExpiringSoonReason.both);
  });

  test('expired wins over an old sync', () {
    final older = now.subtract(const Duration(days: 91));
    expect(
      assessFreshness(
        now: now,
        bundleVersion: '2026-01-01T00:00:00Z',
        lastSyncEpochSeconds: _epoch(older),
        expiresOn: const ['2027-11-02', '2020-01-01'],
      ).staleness,
      Staleness.expired,
    );
  });

  test('device date before publish does not treat documents as expired', () {
    final early = DateTime.utc(2026, 11, 3, 2, 30, 34);
    final freshness = assessFreshness(
      now: early,
      bundleVersion: '2026-11-03T02:30:35Z',
      lastSyncEpochSeconds: _epoch(early),
      expiresOn: const ['2020-01-01'],
    );
    expect(freshness.clockUnsure, isTrue);
    expect(freshness.staleness, Staleness.fresh);

    final atPublish = DateTime.utc(2026, 11, 3, 2, 30, 35);
    final onTime = assessFreshness(
      now: atPublish,
      bundleVersion: '2026-11-03T02:30:35Z',
      lastSyncEpochSeconds: _epoch(atPublish),
      expiresOn: const ['2020-01-01'],
    );
    expect(onTime.clockUnsure, isFalse);
    expect(onTime.staleness, Staleness.expired);
  });

  test('an early clock still marks an old sync as expiring soon', () {
    final early = DateTime.utc(2026, 11, 3, 2, 30, 34);
    final freshness = assessFreshness(
      now: early,
      bundleVersion: '2026-11-03T02:30:35Z',
      lastSyncEpochSeconds: _epoch(early.subtract(const Duration(days: 91))),
      expiresOn: const ['2020-01-01'],
    );
    expect(freshness.clockUnsure, isTrue);
    expect(freshness.staleness, Staleness.expiringSoon);
    expect(freshness.expiringSoonReason, ExpiringSoonReason.lastSync);
  });

  test('a malformed bundleVersion does not throw', () {
    final freshness = assessFreshness(
      now: now,
      bundleVersion: 'not-a-timestamp',
      lastSyncEpochSeconds: _epoch(now),
      expiresOn: const ['2020-01-01'],
    );
    expect(freshness.clockUnsure, isFalse);
    expect(freshness.staleness, Staleness.expired);
  });

  test('a missing sync and a blank expiry stay fresh', () {
    expect(
      assessFreshness(
        now: now,
        bundleVersion: '2026-01-01T00:00:00Z',
        lastSyncEpochSeconds: null,
        expiresOn: const [''],
      ).staleness,
      Staleness.fresh,
    );
  });

  test('expired document still retrieves', () {
    final bundleDir = _findUp('corpus/sample-bundle');
    final parsed = parseBundleManifest(
      File('${bundleDir.path}/bundle.json').readAsStringSync(),
    );
    final manifest = BundleManifest(
      formatVersion: parsed.formatVersion,
      sequence: parsed.sequence,
      bundleVersion: parsed.bundleVersion,
      documents: [
        for (final document in parsed.documents)
          BundleDocument(
            id: document.id,
            title: document.title,
            agency: document.agency,
            publishedDate: document.publishedDate,
            reviewedOn: document.reviewedOn,
            expiresOn: '2020-01-01',
            licence: document.licence,
            country: document.country,
            hazards: document.hazards,
            sourceUri: document.sourceUri,
            sourceType: document.sourceType,
            checksum: document.checksum,
            fileChecksum: document.fileChecksum,
            chunks: document.chunks,
          ),
      ],
    );
    final temp = Directory.systemTemp.createTempSync('sheltr-stale-');
    final store = ContentStore.open(temp);
    try {
      store.install(manifest, (document) {
        return parseDocumentFileBytes(
          File('${bundleDir.path}/documents/${document.id}.json')
              .readAsBytesSync(),
        );
      });
      expect(
        store.documents().map((document) => document.expiresOn),
        everyElement('2020-01-01'),
      );
      final result = Retriever(store).retrieve(
        const RetrievalQuery(text: 'what should I do during an earthquake'),
      );
      expect(result.kind, 'passages');
      expect(result.passages, isNotEmpty);
    } finally {
      store.close();
      temp.deleteSync(recursive: true);
    }
  });
}

Directory _findUp(String relative) {
  var dir = Directory.current;
  while (true) {
    final candidate = Directory('${dir.path}/$relative');
    if (candidate.existsSync()) {
      return candidate;
    }
    final parent = dir.parent;
    if (parent.path == dir.path) {
      throw StateError('$relative not found from ${Directory.current.path}');
    }
    dir = parent;
  }
}

int _epoch(DateTime time) => time.toUtc().millisecondsSinceEpoch ~/ 1000;
