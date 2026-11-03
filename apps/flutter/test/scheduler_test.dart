import 'package:flutter_test/flutter_test.dart';
import 'package:sheltr/sync/scheduler.dart';
import 'package:sheltr_core/settings/app_settings.dart';
import 'package:sheltr_core/settings/sync_due.dart';

void main() {
  final clock = DateTime.utc(2026, 6, 15, 12);

  AppSettings settings(SyncFrequency syncFrequency) {
    return AppSettings.defaults.copyWith(syncFrequency: syncFrequency);
  }

  test('manual frequency returns null due', () async {
    expect(
      await scheduleSyncOnLaunch(
        settings: settings(SyncFrequency.manual),
        lastSync: null,
        now: clock,
        onUnmeteredNetwork: true,
      ),
      isNull,
    );
  });

  test('metered network blocks due but keeps schedule delay', () async {
    final lastSync = clock.subtract(const Duration(seconds: 604799));
    final weekly = settings(SyncFrequency.weekly);

    expect(
      await scheduleSyncOnLaunch(
        settings: weekly,
        lastSync: lastSync,
        now: clock,
        onUnmeteredNetwork: false,
      ),
      isNull,
    );

    final schedule = nextSyncDue(
      weekly,
      lastSync,
      clock,
      onUnmeteredNetwork: true,
    );
    expect(syncInitialDelay(schedule, clock), const Duration(seconds: 1));
  });

  test('initial delay waits until the due instant', () {
    final due = clock.add(const Duration(hours: 2));
    expect(syncInitialDelay(due, clock), const Duration(hours: 2));
    expect(syncInitialDelay(clock, clock), isNull);
    expect(
      syncInitialDelay(clock.subtract(const Duration(hours: 1)), clock),
      isNull,
    );
  });
}
