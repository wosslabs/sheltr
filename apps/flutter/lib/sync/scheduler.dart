import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:sheltr_core/sheltr_core.dart';
import 'package:workmanager/workmanager.dart';

/// BGTaskScheduler identifier. It matches `Info.plist`.
const sheltrSyncTaskId = 'com.sheltr.sync';

const _taskName = 'sheltrSync';

/// OS entry point for the periodic sync. This task does not open a socket.
@pragma('vm:entry-point')
void sheltrSyncDispatcher() {
  Workmanager().executeTask((taskName, inputData) async {
    return true;
  });
}

/// Asks [nextSyncDue] on launch and registers a best-effort background
/// callback for the same interval: 86400, 604800, or 2592000 seconds.
///
/// Manual frequency cancels a callback registered earlier. A metered network
/// does not make a sync due unless mobile data is allowed; the registered
/// callback still waits for an unmetered link in that case. Registration
/// does nothing under `FLUTTER_TEST`. A failure talking to the OS does not
/// throw, so launch continues.
Future<DateTime?> scheduleSyncOnLaunch({
  required AppSettings settings,
  DateTime? lastSync,
  DateTime? now,
  bool? onUnmeteredNetwork,
}) async {
  final clock = now ?? DateTime.now().toUtc();
  final unmetered = onUnmeteredNetwork ?? await _deviceIsUnmetered();
  final due = nextSyncDue(
    settings,
    lastSync,
    clock,
    onUnmeteredNetwork: unmetered,
  );
  final seconds = syncIntervalSeconds[settings.syncFrequency];
  if (seconds == null) {
    await _cancelBackground();
    return null;
  }
  // A metered network makes [due] null, but the interval still stands.
  // Ask again as if the link were unmetered so the first wait is the
  // remaining schedule, not "as soon as wifi appears".
  final schedule =
      due ?? nextSyncDue(settings, lastSync, clock, onUnmeteredNetwork: true);
  await _registerBackground(
    settings,
    Duration(seconds: seconds),
    syncInitialDelay(schedule, clock),
  );
  return due;
}

Duration? syncInitialDelay(DateTime? due, DateTime clock) {
  if (due == null || !due.isAfter(clock)) {
    return null;
  }
  final wait = due.difference(clock);
  if (wait <= Duration.zero) {
    return null;
  }
  return wait;
}

Future<bool> _deviceIsUnmetered() async {
  if (const bool.fromEnvironment('FLUTTER_TEST')) {
    return true;
  }
  try {
    final results = await Connectivity().checkConnectivity();
    return results.contains(ConnectivityResult.wifi) ||
        results.contains(ConnectivityResult.ethernet);
  } catch (_) {
    return false;
  }
}

Future<void> _registerBackground(
  AppSettings settings,
  Duration interval,
  Duration? initialDelay,
) async {
  if (const bool.fromEnvironment('FLUTTER_TEST')) {
    return;
  }
  try {
    await Workmanager().initialize(sheltrSyncDispatcher);
    await Workmanager().registerPeriodicTask(
      sheltrSyncTaskId,
      _taskName,
      frequency: interval,
      initialDelay: initialDelay,
      existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
      constraints: Constraints(
        networkType: settings.allowMobileData
            ? NetworkType.connected
            : NetworkType.unmetered,
      ),
    );
  } catch (_) {
    // Best-effort. The OS can refuse the callback; the app still opens.
  }
}

Future<void> _cancelBackground() async {
  if (const bool.fromEnvironment('FLUTTER_TEST')) {
    return;
  }
  try {
    await Workmanager().initialize(sheltrSyncDispatcher);
    await Workmanager().cancelByUniqueName(sheltrSyncTaskId);
  } catch (_) {
    // Nothing was registered, or the OS rejected the cancel.
  }
}
