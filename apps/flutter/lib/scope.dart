import 'package:flutter/foundation.dart';
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
    this.embedQuery,
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

  /// Embeds the question for vector search. Null keeps keyword search.
  ///
  /// A failure refuses. It does not fall back to keywords, because a keyword
  /// hit on an unrelated annex would skip the vector floor.
  final Future<List<double>> Function(String question)? embedQuery;

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
        oldWidget.model != model ||
        oldWidget.embedQuery != embedQuery;
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
  const seed = Color(0xFF0D3B2E);
  var scheme = ColorScheme.fromSeed(
    seedColor: seed,
    brightness: brightness,
    surface: dark ? const Color(0xFF101814) : const Color(0xFFEEF2F0),
  );
  if (!highContrast) {
    scheme = scheme.copyWith(
      primary: dark ? const Color(0xFF8FCBB0) : const Color(0xFF0D3B2E),
      onPrimary: dark ? const Color(0xFF003828) : Colors.white,
      primaryContainer: dark
          ? const Color(0xFF1F4A3A)
          : const Color(0xFFD5E8DE),
      onPrimaryContainer: dark
          ? const Color(0xFFD5E8DE)
          : const Color(0xFF06281E),
      secondary: dark ? const Color(0xFFA8C5B6) : const Color(0xFF2D6A4F),
      onSecondary: dark ? const Color(0xFF0D3B2E) : Colors.white,
      surfaceContainerLowest: dark
          ? const Color(0xFF0B1210)
          : const Color(0xFFF7FAF8),
      surfaceContainerLow: dark
          ? const Color(0xFF16201C)
          : const Color(0xFFE4EBE7),
      surfaceContainerHighest: dark
          ? const Color(0xFF243029)
          : const Color(0xFFCDD9D2),
      outline: dark ? const Color(0xFF8AA396) : const Color(0xFF5C7368),
      onSurface: dark ? const Color(0xFFE4EDE8) : const Color(0xFF14201C),
    );
  } else {
    scheme = scheme.copyWith(
      surface: dark ? Colors.black : Colors.white,
      onSurface: dark ? Colors.white : Colors.black,
      primary: dark ? const Color(0xFFFFEB3B) : const Color(0xFF003828),
      onPrimary: dark ? Colors.black : Colors.white,
      outline: dark ? Colors.white : Colors.black,
    );
  }
  final button = ButtonStyle(
    minimumSize: const WidgetStatePropertyAll(
      Size(minTouchTarget, minTouchTarget),
    ),
    tapTargetSize: MaterialTapTargetSize.padded,
    shape: WidgetStatePropertyAll(
      RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
  );
  final textTheme = _sheltrTextTheme(brightness, scheme);
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: 'IBMPlexSans',
    textTheme: textTheme,
    primaryTextTheme: textTheme,
    visualDensity: VisualDensity.standard,
    materialTapTargetSize: MaterialTapTargetSize.padded,
    scaffoldBackgroundColor: scheme.surface,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
      elevation: 0,
      scrolledUnderElevation: 0.5,
      centerTitle: false,
      titleTextStyle: textTheme.titleLarge?.copyWith(
        fontFamily: 'SourceSerif4',
        fontWeight: FontWeight.w600,
        color: scheme.onSurface,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: scheme.surfaceContainerLowest,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: scheme.outline.withValues(alpha: 0.28)),
      ),
      margin: EdgeInsets.zero,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerLowest,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.outline.withValues(alpha: 0.55)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.primary, width: 2),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: button.copyWith(
        backgroundColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) {
            return scheme.onSurface.withValues(alpha: 0.18);
          }
          return scheme.primary;
        }),
        foregroundColor: WidgetStatePropertyAll(scheme.onPrimary),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: button.copyWith(
        side: WidgetStatePropertyAll(
          BorderSide(color: scheme.outline, width: 1.4),
        ),
        foregroundColor: WidgetStatePropertyAll(scheme.onSurface),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: button),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        minimumSize: const Size(minTouchTarget, minTouchTarget),
        tapTargetSize: MaterialTapTargetSize.padded,
      ),
    ),
  );
}

TextTheme _sheltrTextTheme(Brightness brightness, ColorScheme scheme) {
  final base = brightness == Brightness.dark
      ? Typography.material2021(platform: TargetPlatform.android).white
      : Typography.material2021(platform: TargetPlatform.android).black;
  return base
      .apply(
        fontFamily: 'IBMPlexSans',
        bodyColor: scheme.onSurface,
        displayColor: scheme.onSurface,
      )
      .copyWith(
        displaySmall: base.displaySmall?.copyWith(
          fontFamily: 'SourceSerif4',
          fontWeight: FontWeight.w600,
          letterSpacing: -0.4,
          height: 1.15,
        ),
        headlineLarge: base.headlineLarge?.copyWith(
          fontFamily: 'SourceSerif4',
          fontWeight: FontWeight.w600,
          letterSpacing: -0.3,
          height: 1.2,
        ),
        headlineMedium: base.headlineMedium?.copyWith(
          fontFamily: 'SourceSerif4',
          fontWeight: FontWeight.w600,
          height: 1.22,
        ),
        headlineSmall: base.headlineSmall?.copyWith(
          fontFamily: 'SourceSerif4',
          fontWeight: FontWeight.w600,
          height: 1.25,
        ),
        titleLarge: base.titleLarge?.copyWith(
          fontFamily: 'SourceSerif4',
          fontWeight: FontWeight.w600,
          height: 1.3,
        ),
        titleMedium: base.titleMedium?.copyWith(
          fontFamily: 'IBMPlexSans',
          fontWeight: FontWeight.w600,
          letterSpacing: 0.1,
        ),
        bodyLarge: base.bodyLarge?.copyWith(height: 1.5, fontSize: 17),
        bodyMedium: base.bodyMedium?.copyWith(height: 1.45),
        labelLarge: base.labelLarge?.copyWith(
          fontWeight: FontWeight.w600,
          letterSpacing: 0.2,
        ),
      );
}

/// The country answers are limited to. In a debug build it opens Settings.
class CountryLine extends StatelessWidget {
  const CountryLine({super.key});

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final scheme = Theme.of(context).colorScheme;
    final code = scope.settings.country;
    final name = code == null
        ? scope.t('settings_country_not_set')
        : countryNames[code]?.forLanguage(scope.languageCode) ?? code;
    final label = '${scope.t('settings_country_label')}: $name';
    final chip = Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: scheme.outline.withValues(alpha: 0.45)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.public, size: 18, color: scheme.secondary),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              label,
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
    if (!kDebugMode) {
      return chip;
    }
    return Align(
      alignment: Alignment.centerLeft,
      child: Semantics(
        button: true,
        label: label,
        child: InkWell(
          onTap: () => Navigator.of(context).pushNamed('/settings'),
          borderRadius: BorderRadius.circular(999),
          child: chip,
        ),
      ),
    );
  }
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
      // Keep the accessible name short; the hint is the visible prompt.
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        floatingLabelBehavior: FloatingLabelBehavior.auto,
        prefixIcon: const Icon(Icons.search),
        border: const OutlineInputBorder(),
        constraints: const BoxConstraints(
          minHeight: minTouchTarget,
          minWidth: minTouchTarget,
        ),
      ),
    );
  }
}

/// Soft page background wash behind screen content.
class SheltrPageBackground extends StatelessWidget {
  const SheltrPageBackground({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: dark
              ? [scheme.surface, const Color(0xFF0B1210)]
              : [const Color(0xFFF4F8F5), scheme.surface, const Color(0xFFE7EFEA)],
          stops: const [0, 0.45, 1],
        ),
      ),
      child: child,
    );
  }
}
