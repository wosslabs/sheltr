import 'package:sheltr_core/sheltr_core.dart';

/// The web UI test has no background scheduler. Sync stays a button.
Future<DateTime?> scheduleSyncOnLaunch({
  required AppSettings settings,
  DateTime? lastSync,
  DateTime? now,
  bool? onUnmeteredNetwork,
}) async {
  return null;
}
