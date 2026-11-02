import { useCallback, useMemo } from 'react';
import { useServices } from '../platform/AppContext';
import { resolveLocale, translate, type Locale, type MessageKey } from '../i18n';

export type TFunction = (key: MessageKey, values?: Record<string, string | number>) => string;

/** Translation for the language the user chose in Settings (or the device language). */
export function useT(): { t: TFunction; locale: Locale } {
  const { settings } = useServices();
  const locale = useMemo(() => resolveLocale(settings.language), [settings.language]);
  const t = useCallback<TFunction>((key, values) => translate(key, locale, values), [locale]);
  return { t, locale };
}
