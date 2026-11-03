import 'package:flutter/material.dart';
import 'package:sheltr_core/sheltr_core.dart';

import 'l10n/strings.dart';

/// Settings, strings, and the answer the screens render.
class SheltrScope extends InheritedWidget {
  const SheltrScope({
    required this.strings,
    required this.settings,
    required this.languageCode,
    required this.answer,
    required this.documents,
    required this.store,
    required this.now,
    required this.trustedKeys,
    required this.updateSettings,
    required super.child,
    this.model,
    super.key,
  });

  final AppStrings strings;
  final AppSettings settings;
  final String languageCode;
  final RetrievalResult? answer;
  final List<StoredDocument> documents;

  /// Open content database. Search reads it through [Retriever].
  final ContentStore? store;

  /// Instant used for review dates, the published-bundle clock check, and key dates.
  final DateTime Function() now;

  /// Compiled publisher keys. At most two. Empty when this build has none.
  final List<TrustedKey> trustedKeys;
  final ValueChanged<AppSettings> updateSettings;

  /// Optional passage rewriter. The answer screen does not call it while
  /// [AppSettings.modelEnabled] is false.
  final PassageModel? model;

  String t(String key, [Map<String, Object?> values = const {}]) {
    return strings.t(languageCode, key, values);
  }

  static SheltrScope of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<SheltrScope>();
    assert(scope != null, 'SheltrScope is missing');
    return scope!;
  }

  @override
  bool updateShouldNotify(SheltrScope oldWidget) {
    return oldWidget.settings != settings ||
        oldWidget.languageCode != languageCode ||
        oldWidget.answer != answer ||
        oldWidget.documents != documents ||
        oldWidget.store != store ||
        oldWidget.now != now ||
        oldWidget.trustedKeys != trustedKeys ||
        oldWidget.strings != strings ||
        oldWidget.model != model;
  }
}

/// Copy that is true for this freshness. Null when the guidance is fresh.
///
/// The answer screen and a cited card step use this same mapping.
String? stalenessWarningKey(Freshness freshness) {
  if (freshness.staleness == Staleness.expired) {
    return 'answer_expired_warning';
  }
  switch (freshness.expiringSoonReason) {
    case ExpiringSoonReason.documentDate:
      return 'answer_expiring_soon_warning';
    case ExpiringSoonReason.lastSync:
      return 'answer_sync_old_warning';
    case ExpiringSoonReason.both:
      return 'answer_expiring_soon_and_sync_old_warning';
    case null:
      return null;
  }
}

/// Touch targets are at least 48 logical pixels on both axes.
const minTouchTarget = 48.0;

ThemeData sheltrTheme(AppSettings settings, Brightness brightness) {
  final highContrast = settings.highContrast;
  final dark = brightness == Brightness.dark;
  var scheme = ColorScheme.fromSeed(
    seedColor: const Color(0xFF0B57D0),
    brightness: brightness,
  );
  if (highContrast) {
    scheme = scheme.copyWith(
      surface: dark ? Colors.black : Colors.white,
      onSurface: dark ? Colors.white : Colors.black,
      primary: dark ? const Color(0xFFFFEB3B) : const Color(0xFF00339E),
      onPrimary: dark ? Colors.black : Colors.white,
      outline: dark ? Colors.white : Colors.black,
    );
  }
  final button = ButtonStyle(
    minimumSize: const WidgetStatePropertyAll(
      Size(minTouchTarget, minTouchTarget),
    ),
    tapTargetSize: MaterialTapTargetSize.padded,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    visualDensity: VisualDensity.standard,
    materialTapTargetSize: MaterialTapTargetSize.padded,
    filledButtonTheme: FilledButtonThemeData(style: button),
    outlinedButtonTheme: OutlinedButtonThemeData(style: button),
    textButtonTheme: TextButtonThemeData(style: button),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        minimumSize: const Size(minTouchTarget, minTouchTarget),
        tapTargetSize: MaterialTapTargetSize.padded,
      ),
    ),
  );
}

/// A labelled icon button at least 48 logical pixels square.
class SheltrIconButton extends StatelessWidget {
  const SheltrIconButton({
    required this.label,
    required this.icon,
    required this.onPressed,
    super.key,
  });

  final String label;
  final IconData icon;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      enabled: true,
      label: label,
      onTap: onPressed,
      excludeSemantics: true,
      child: IconButton(tooltip: label, onPressed: onPressed, icon: Icon(icon)),
    );
  }
}

class SheltrTextField extends StatelessWidget {
  const SheltrTextField({
    required this.label,
    required this.controller,
    this.hint,
    this.onChanged,
    this.onSubmitted,
    super.key,
  });

  final String label;
  final String? hint;
  final TextEditingController controller;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      onChanged: onChanged,
      onSubmitted: onSubmitted,
      textInputAction: TextInputAction.search,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        border: const OutlineInputBorder(),
        constraints: const BoxConstraints(
          minHeight: minTouchTarget,
          minWidth: minTouchTarget,
        ),
      ),
    );
  }
}
