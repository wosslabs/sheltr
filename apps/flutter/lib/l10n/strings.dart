import 'package:flutter/widgets.dart';
import 'package:sheltr_core/sheltr_core.dart';

import 'catalogs.dart';

/// UI strings for English, Spanish, and French.
class AppStrings {
  const AppStrings();

  String t(
    String languageCode,
    String key, [
    Map<String, Object?> values = const {},
  ]) {
    final catalog = catalogs[languageCode] ?? catalogs['en']!;
    var text = catalog[key] ?? catalogs['en']![key] ?? key;
    for (final entry in values.entries) {
      text = text.replaceAll('%{${entry.key}}', '${entry.value}');
    }
    return text;
  }
}

/// Menu language for [language], or English when the device language is not
/// English, Spanish, or French.
String resolveLanguage(Language language, List<Locale> deviceLocales) {
  if (language != Language.system) {
    return language.name;
  }
  for (final locale in deviceLocales) {
    switch (locale.languageCode.toLowerCase()) {
      case 'en':
      case 'es':
      case 'fr':
        return locale.languageCode.toLowerCase();
    }
  }
  return 'en';
}
