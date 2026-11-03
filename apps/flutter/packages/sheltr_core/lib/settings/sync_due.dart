import 'app_settings.dart';

/// When the next automatic sync may start.
///
/// Manual frequency returns null. A metered network also returns null unless
/// [AppSettings.allowMobileData] is true. A first sync ([lastSync] null) is
/// due at [now]. Otherwise the due instant is [lastSync] plus 86400, 604800,
/// or 2592000 seconds.
///
/// [onUnmeteredNetwork] is the boundary the launch check already applies:
/// sync waits for an unmetered link unless mobile data is allowed. It
/// defaults to true so a caller that only has the schedule can omit it.
DateTime? nextSyncDue(
  AppSettings settings,
  DateTime? lastSync,
  DateTime now, {
  bool onUnmeteredNetwork = true,
}) {
  final intervalSeconds = syncIntervalSeconds[settings.syncFrequency];
  if (intervalSeconds == null) {
    return null;
  }
  if (!onUnmeteredNetwork && !settings.allowMobileData) {
    return null;
  }
  if (lastSync == null) {
    return now;
  }
  return lastSync.add(Duration(seconds: intervalSeconds));
}
