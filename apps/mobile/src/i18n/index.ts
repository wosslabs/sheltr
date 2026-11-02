import { getLocales } from 'expo-localization';
import { I18n } from 'i18n-js';
import type { Language } from '../core/settings/appSettings';
import en from './locales/en.json';
import es from './locales/es.json';
import fr from './locales/fr.json';

export type Locale = 'en' | 'es' | 'fr';
export const LOCALES: Locale[] = ['en', 'es', 'fr'];

export type MessageKey = keyof typeof en;

const i18n = new I18n({ en, es, fr });
i18n.defaultLocale = 'en';
i18n.enableFallback = true;

/** The locale to show for a language setting: the setting itself, or the device language when it is 'system'. */
export function resolveLocale(language: Language, deviceLanguageCodes: readonly string[] = deviceCodes()): Locale {
  if (language !== 'system') return language;
  for (const code of deviceLanguageCodes) {
    const base = code.toLowerCase().split(/[-_]/)[0];
    if ((LOCALES as string[]).includes(base)) return base as Locale;
  }
  return 'en';
}

function deviceCodes(): string[] {
  try {
    return getLocales().map((l) => l.languageTag);
  } catch {
    return [];
  }
}

/** Translates [key] in [locale]. Positional values are passed as p1, p2: t('x', 'es', { p1: 'a' }). */
export function translate(key: MessageKey, locale: Locale, values: Record<string, string | number> = {}): string {
  return i18n.t(key, { locale, ...values });
}
