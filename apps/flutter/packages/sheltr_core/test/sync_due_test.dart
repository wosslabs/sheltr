import 'package:sheltr_core/settings/app_settings.dart';
import 'package:sheltr_core/settings/sync_due.dart';
import 'package:test/test.dart';

void main() {
  final now = DateTime.utc(2026, 6, 15, 12);

  AppSettings settings(
    SyncFrequency syncFrequency, {
    bool allowMobileData = false,
  }) {
    return AppSettings.defaults.copyWith(
      syncFrequency: syncFrequency,
      allowMobileData: allowMobileData,
    );
  }

  test('never auto-syncs when manual', () {
    expect(
      nextSyncDue(
        settings(SyncFrequency.manual, allowMobileData: true),
        null,
        now,
        onUnmeteredNetwork: true,
      ),
      isNull,
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.manual, allowMobileData: true),
        DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
        now,
        onUnmeteredNetwork: true,
      ),
      isNull,
    );
  });

  test('is due when never synced on an unmetered network', () {
    expect(
      nextSyncDue(
        settings(SyncFrequency.weekly),
        null,
        now,
        onUnmeteredNetwork: true,
      ),
      now,
    );
  });

  test('is blocked on metered networks unless mobile data is allowed', () {
    expect(
      nextSyncDue(
        settings(SyncFrequency.weekly),
        null,
        now,
        onUnmeteredNetwork: false,
      ),
      isNull,
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.weekly, allowMobileData: true),
        null,
        now,
        onUnmeteredNetwork: false,
      ),
      now,
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.weekly),
        now.subtract(const Duration(seconds: 10 * 604800)),
        now,
        onUnmeteredNetwork: false,
      ),
      isNull,
    );
  });

  test('is due exactly at the interval, not a second before', () {
    expect(
      nextSyncDue(
        settings(SyncFrequency.daily),
        now.subtract(const Duration(seconds: 86400)),
        now,
        onUnmeteredNetwork: true,
      ),
      now,
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.daily),
        now.subtract(const Duration(seconds: 86399)),
        now,
        onUnmeteredNetwork: true,
      ),
      now.add(const Duration(seconds: 1)),
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.weekly),
        now.subtract(const Duration(seconds: 604800)),
        now,
        onUnmeteredNetwork: true,
      ),
      now,
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.weekly),
        now.subtract(const Duration(seconds: 604799)),
        now,
        onUnmeteredNetwork: true,
      ),
      now.add(const Duration(seconds: 1)),
    );
  });

  test('uses 30 days for monthly', () {
    const thirty = 30 * 86400;
    expect(thirty, 2592000);
    expect(
      nextSyncDue(
        settings(SyncFrequency.monthly),
        now.subtract(const Duration(seconds: thirty)),
        now,
        onUnmeteredNetwork: true,
      ),
      now,
    );
    expect(
      nextSyncDue(
        settings(SyncFrequency.monthly),
        now.subtract(const Duration(seconds: thirty - 1)),
        now,
        onUnmeteredNetwork: true,
      ),
      now.add(const Duration(seconds: 1)),
    );
  });

  test('is not due for a last sync in the future', () {
    final lastSync = now.add(const Duration(seconds: 500));
    final due = nextSyncDue(
      settings(SyncFrequency.daily),
      lastSync,
      now,
      onUnmeteredNetwork: true,
    );
    expect(due, lastSync.add(const Duration(seconds: 86400)));
    expect(due!.isAfter(now), isTrue);
  });
}
