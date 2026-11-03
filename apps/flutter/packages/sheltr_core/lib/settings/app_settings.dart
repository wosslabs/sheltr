import 'dart:convert';

/// How often an automatic sync may run. Manual never starts one by itself.
enum SyncFrequency { manual, daily, weekly, monthly }

/// Menu language. [Language.system] follows the device, then English.
enum Language { system, en, es, fr }

/// Text scale factors the settings screen offers.
final textScaleFactors = <double>{1.0, 1.2, 1.4};

/// Seconds between automatic syncs. Manual is null. Monthly is 30 days.
const syncIntervalSeconds = <SyncFrequency, int?>{
  SyncFrequency.manual: null,
  SyncFrequency.daily: 86400,
  SyncFrequency.weekly: 604800,
  SyncFrequency.monthly: 2592000,
};

class _Keep {
  const _Keep();
}

const _keep = _Keep();

/// User settings. Change a value with [copyWith].
class AppSettings {
  const AppSettings({
    this.syncFrequency = SyncFrequency.weekly,
    this.allowMobileData = false,
    this.country,
    this.textScale = 1.0,
    this.highContrast = false,
    this.language = Language.system,
    this.modelEnabled = false,
  }) : assert(
         textScale == 1.0 || textScale == 1.2 || textScale == 1.4,
         'text scale must be 1.0, 1.2, or 1.4',
       );

  static const defaults = AppSettings();

  final SyncFrequency syncFrequency;

  /// Sync on metered connections too. Off: sync waits for an unmetered network.
  final bool allowMobileData;

  /// ISO 3166-1 alpha-2 code in uppercase ASCII, or null when unset.
  final String? country;

  final double textScale;
  final bool highContrast;
  final Language language;

  /// On-device restatement of cited passages. Off until a device eval passes.
  final bool modelEnabled;

  AppSettings copyWith({
    SyncFrequency? syncFrequency,
    bool? allowMobileData,
    Object? country = _keep,
    double? textScale,
    bool? highContrast,
    Language? language,
    bool? modelEnabled,
  }) {
    return AppSettings(
      syncFrequency: syncFrequency ?? this.syncFrequency,
      allowMobileData: allowMobileData ?? this.allowMobileData,
      country: identical(country, _keep) ? this.country : country as String?,
      textScale: textScale ?? this.textScale,
      highContrast: highContrast ?? this.highContrast,
      language: language ?? this.language,
      modelEnabled: modelEnabled ?? this.modelEnabled,
    );
  }

  @override
  bool operator ==(Object other) {
    return other is AppSettings &&
        other.syncFrequency == syncFrequency &&
        other.allowMobileData == allowMobileData &&
        other.country == country &&
        other.textScale == textScale &&
        other.highContrast == highContrast &&
        other.language == language &&
        other.modelEnabled == modelEnabled;
  }

  @override
  int get hashCode => Object.hash(
    syncFrequency,
    allowMobileData,
    country,
    textScale,
    highContrast,
    language,
    modelEnabled,
  );

  @override
  String toString() {
    return 'AppSettings(syncFrequency: $syncFrequency, allowMobileData: $allowMobileData, '
        'country: $country, textScale: $textScale, highContrast: $highContrast, '
        'language: $language, modelEnabled: $modelEnabled)';
  }
}

/// Two uppercase ASCII letters.
bool isValidCountry(String code) => RegExp(r'^[A-Z]{2}$').hasMatch(code);

/// Throws unless [settings] can be stored.
void assertValidSettings(AppSettings settings) {
  final country = settings.country;
  if (country != null && !isValidCountry(country)) {
    throw RangeError('country must be null or two uppercase ASCII letters');
  }
  if (!textScaleFactors.contains(settings.textScale)) {
    throw RangeError('text scale must be 1.0, 1.2, or 1.4');
  }
}

/// Reads settings from stored JSON.
///
/// A missing or invalid field falls back on its own. Damaged JSON must not
/// stop the app from starting, so this never throws.
AppSettings parseSettings(String? json) {
  if (json == null) {
    return AppSettings.defaults;
  }
  Object? decoded;
  try {
    decoded = jsonDecode(json);
  } on FormatException {
    return AppSettings.defaults;
  }
  if (decoded is! Map) {
    return AppSettings.defaults;
  }
  return AppSettings(
    syncFrequency: _oneOf(
      SyncFrequency.values,
      decoded['syncFrequency'],
      SyncFrequency.weekly,
    ),
    allowMobileData: decoded['allowMobileData'] is bool
        ? decoded['allowMobileData'] as bool
        : false,
    country: _country(decoded['country']),
    textScale: _textScale(decoded['textScale']),
    highContrast: decoded['highContrast'] is bool
        ? decoded['highContrast'] as bool
        : false,
    language: _oneOf(Language.values, decoded['language'], Language.system),
    modelEnabled: decoded['modelEnabled'] is bool
        ? decoded['modelEnabled'] as bool
        : false,
  );
}

T _oneOf<T extends Enum>(List<T> allowed, Object? value, T fallback) {
  if (value is! String) {
    return fallback;
  }
  for (final item in allowed) {
    if (item.name == value) {
      return item;
    }
  }
  return fallback;
}

String? _country(Object? value) {
  if (value is String && isValidCountry(value)) {
    return value;
  }
  return null;
}

double _textScale(Object? value) {
  if (value is! num) {
    return 1.0;
  }
  final scale = value.toDouble();
  for (final allowed in textScaleFactors) {
    if ((scale - allowed).abs() < 0.0001) {
      return allowed;
    }
  }
  return 1.0;
}
