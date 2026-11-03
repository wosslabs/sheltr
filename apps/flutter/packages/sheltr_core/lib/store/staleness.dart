/// How current the installed guidance is, compared with the device clock.
enum Staleness { fresh, expiringSoon, expired }

/// Why a result is [Staleness.expiringSoon].
enum ExpiringSoonReason { documentDate, lastSync, both }

/// Review-date state for one answer.
///
/// When [clockUnsure] is true the device instant is earlier than the bundle's
/// published timestamp. Document dates are ignored then, so [staleness] is
/// not [Staleness.expired]. [expiringSoonReason] is set only for
/// [Staleness.expiringSoon].
class Freshness {
  const Freshness({
    required this.staleness,
    required this.clockUnsure,
    this.expiringSoonReason,
  });

  final Staleness staleness;
  final bool clockUnsure;

  /// The document date, the last sync, or both. Null unless [staleness] is
  /// [Staleness.expiringSoon].
  final ExpiringSoonReason? expiringSoonReason;
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
      expiringSoonReason: syncOld ? ExpiringSoonReason.lastSync : null,
    );
  }

  final today = _utcDate(now);
  final soonUntil = today.add(const Duration(days: 30));
  var dateSoon = false;
  for (final raw in expiresOn) {
    final expiry = _parseIsoDate(raw);
    if (expiry == null) {
      continue;
    }
    if (expiry.isBefore(today)) {
      return const Freshness(staleness: Staleness.expired, clockUnsure: false);
    }
    if (!expiry.isAfter(soonUntil)) {
      dateSoon = true;
    }
  }
  final reason = _expiringSoonReason(dateSoon: dateSoon, syncOld: syncOld);
  return Freshness(
    staleness: reason == null ? Staleness.fresh : Staleness.expiringSoon,
    clockUnsure: false,
    expiringSoonReason: reason,
  );
}

ExpiringSoonReason? _expiringSoonReason({
  required bool dateSoon,
  required bool syncOld,
}) {
  if (dateSoon && syncOld) {
    return ExpiringSoonReason.both;
  }
  if (dateSoon) {
    return ExpiringSoonReason.documentDate;
  }
  if (syncOld) {
    return ExpiringSoonReason.lastSync;
  }
  return null;
}

bool _clockIsBeforePublish(DateTime now, String? bundleVersion) {
  final published = _parseBundleVersion(bundleVersion);
  if (published == null) {
    return false;
  }
  return now.toUtc().isBefore(published.toUtc());
}

/// Null when [bundleVersion] is missing or not a timestamp. A bad value is
/// not treated as an early clock.
DateTime? _parseBundleVersion(String? bundleVersion) {
  if (bundleVersion == null) {
    return null;
  }
  try {
    return DateTime.parse(bundleVersion);
  } on FormatException {
    return null;
  }
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
