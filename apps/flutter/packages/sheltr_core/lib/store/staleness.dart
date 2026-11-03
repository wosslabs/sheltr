/// How current the installed guidance is, compared with the device clock.
enum Staleness { fresh, expiringSoon, expired }

/// Review-date state for one answer.
///
/// When [clockUnsure] is true the device instant is earlier than the bundle's
/// published timestamp. Document dates are ignored then, so [staleness] is
/// not [Staleness.expired].
class Freshness {
  const Freshness({required this.staleness, required this.clockUnsure});

  final Staleness staleness;
  final bool clockUnsure;
}

/// A document expiring on this calendar day or within the next 30 is
/// [Staleness.expiringSoon]. A last successful sync older than 90 days is
/// the same. [Staleness.expired] wins when an `expiresOn` date is before
/// [now]'s UTC date, unless the clock is earlier than [bundleVersion].
Freshness assessFreshness({
  required DateTime now,
  required String? bundleVersion,
  required int? lastSyncEpochSeconds,
  required Iterable<String> expiresOn,
}) {
  final clockUnsure = _clockIsBeforePublish(now, bundleVersion);
  final syncOld = _syncOlderThan90Days(now, lastSyncEpochSeconds);
  if (clockUnsure) {
    return Freshness(
      staleness: syncOld ? Staleness.expiringSoon : Staleness.fresh,
      clockUnsure: true,
    );
  }

  final today = _utcDate(now);
  final soonUntil = today.add(const Duration(days: 30));
  var expiringSoon = syncOld;
  for (final raw in expiresOn) {
    final expiry = _parseIsoDate(raw);
    if (expiry == null) {
      continue;
    }
    if (expiry.isBefore(today)) {
      return const Freshness(staleness: Staleness.expired, clockUnsure: false);
    }
    if (!expiry.isAfter(soonUntil)) {
      expiringSoon = true;
    }
  }
  return Freshness(
    staleness: expiringSoon ? Staleness.expiringSoon : Staleness.fresh,
    clockUnsure: false,
  );
}

bool _clockIsBeforePublish(DateTime now, String? bundleVersion) {
  if (bundleVersion == null) {
    return false;
  }
  return now.toUtc().isBefore(DateTime.parse(bundleVersion).toUtc());
}

bool _syncOlderThan90Days(DateTime now, int? lastSyncEpochSeconds) {
  if (lastSyncEpochSeconds == null) {
    return false;
  }
  final synced = DateTime.fromMillisecondsSinceEpoch(
    lastSyncEpochSeconds * 1000,
    isUtc: true,
  );
  return now.toUtc().difference(synced) > const Duration(days: 90);
}

DateTime _utcDate(DateTime instant) {
  final utc = instant.toUtc();
  return DateTime.utc(utc.year, utc.month, utc.day);
}

final _isoDate = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$');

/// `YYYY-MM-DD` as a UTC date, or null when [text] is not that shape.
DateTime? _parseIsoDate(String text) {
  final match = _isoDate.firstMatch(text);
  if (match == null) {
    return null;
  }
  final year = int.parse(match.group(1)!);
  final month = int.parse(match.group(2)!);
  final day = int.parse(match.group(3)!);
  final date = DateTime.utc(year, month, day);
  if (date.year != year || date.month != month || date.day != day) {
    return null;
  }
  return date;
}
