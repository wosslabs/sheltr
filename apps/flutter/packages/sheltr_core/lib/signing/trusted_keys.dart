import 'dart:typed_data';

/// One key compiled into the app. A list holds at most two.
class TrustedKey {
  const TrustedKey({
    required this.id,
    required this.publicKeyHex,
    required this.notAfter,
  });

  final String id;

  /// Raw 32-byte Ed25519 public key, 64 lower-case hex characters.
  final String publicKeyHex;

  /// `YYYY-MM-DD`. A date before today warns. The bundle still verifies.
  final String notAfter;
}

/// The key named by `signedBy`, or a rejection when that id is not listed.
sealed class TrustedKeyDecision {
  const TrustedKeyDecision();
}

/// [datePassed] is true when [notAfter] is before today. The signature still counts.
final class TrustedKeyAccepted extends TrustedKeyDecision {
  const TrustedKeyAccepted({required this.publicKey, required this.datePassed});

  final Uint8List publicKey;
  final bool datePassed;
}

/// `signedBy` is missing or names no compiled key.
final class TrustedKeyRejected extends TrustedKeyDecision {
  const TrustedKeyRejected();
}

final _isoDate = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$');
final _publicKeyHex = RegExp(r'^[0-9a-f]{64}$');

/// Rejects a list longer than two keys, a bad hex key, a bad date, or a duplicate id.
List<TrustedKey> compileTrustedKeys(List<TrustedKey> keys) {
  if (keys.length > 2) {
    throw ArgumentError('A compiled key list holds at most two keys');
  }
  final ids = <String>{};
  for (final key in keys) {
    if (key.id.isEmpty || !ids.add(key.id)) {
      throw ArgumentError('A trusted key id must be present and unique');
    }
    if (!_publicKeyHex.hasMatch(key.publicKeyHex)) {
      throw ArgumentError('publicKeyHex must be 64 lower-case hex characters');
    }
    if (!_isIsoDate(key.notAfter)) {
      throw ArgumentError("notAfter '${key.notAfter}' is not a calendar date");
    }
  }
  return List<TrustedKey>.unmodifiable(keys);
}

/// Accepts when [signedBy] names a key on the list. An unknown id is rejected.
///
/// [datePassed] does not reject the key. [today] is `YYYY-MM-DD`.
TrustedKeyDecision decideTrustedKey(
  String? signedBy,
  List<TrustedKey> keys,
  String today,
) {
  if (!_isIsoDate(today)) {
    throw ArgumentError("today '$today' is not a calendar date");
  }
  final compiled = compileTrustedKeys(keys);
  if (signedBy == null) {
    return const TrustedKeyRejected();
  }
  for (final key in compiled) {
    if (key.id != signedBy) continue;
    return TrustedKeyAccepted(
      publicKey: _hexToBytes(key.publicKeyHex),
      datePassed: key.notAfter.compareTo(today) < 0,
    );
  }
  return const TrustedKeyRejected();
}

/// True when any compiled key's notAfter is before [today]. The notAfter day itself is not past.
bool keyDatePassed(List<TrustedKey> keys, String today) {
  if (!_isIsoDate(today)) {
    throw ArgumentError("today '$today' is not a calendar date");
  }
  return compileTrustedKeys(keys)
      .any((key) => key.notAfter.compareTo(today) < 0);
}

/// UTC calendar day, `YYYY-MM-DD`.
String utcDateText(DateTime instant) {
  final utc = instant.toUtc();
  final month = utc.month.toString().padLeft(2, '0');
  final day = utc.day.toString().padLeft(2, '0');
  return '${utc.year}-$month-$day';
}

bool _isIsoDate(String value) {
  final match = _isoDate.firstMatch(value);
  if (match == null) return false;
  final year = int.parse(match.group(1)!);
  final month = int.parse(match.group(2)!);
  final day = int.parse(match.group(3)!);
  final date = DateTime.utc(year, month, day);
  return date.year == year && date.month == month && date.day == day;
}

Uint8List _hexToBytes(String hex) {
  final out = Uint8List(hex.length ~/ 2);
  for (var i = 0; i < out.length; i++) {
    out[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
  }
  return out;
}
