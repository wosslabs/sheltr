import en from './locales/en.json';
import es from './locales/es.json';
import fr from './locales/fr.json';
import { resolveLocale, translate } from './index';

describe('translations', () => {
  it('have the same keys in every language', () => {
    const keys = Object.keys(en).sort();
    expect(Object.keys(es).sort()).toEqual(keys);
    expect(Object.keys(fr).sort()).toEqual(keys);
  });

  it('use the same placeholders in every language', () => {
    const placeholders = (s: string) => (s.match(/%\{p\d\}/g) ?? []).sort();
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(placeholders(es[key])).toEqual(placeholders(en[key]));
      expect(placeholders(fr[key])).toEqual(placeholders(en[key]));
    }
  });

  it('have no empty values', () => {
    for (const catalog of [en, es, fr]) for (const value of Object.values(catalog)) expect(value.trim()).not.toBe('');
  });

  it('translate with positional values', () => {
    expect(translate('answer_source_page', 'en', { p1: 3 })).toBe('Page 3');
    expect(translate('answer_source_page', 'es', { p1: 3 })).toBe('Página 3');
    expect(translate('answer_source_page', 'fr', { p1: 3 })).toBe('Page 3');
  });
});

describe('resolveLocale', () => {
  it('uses an explicit language as is', () => expect(resolveLocale('fr', ['es-ES'])).toBe('fr'));
  it('follows the first supported device language for system', () => {
    expect(resolveLocale('system', ['de-DE', 'es-MX', 'fr-FR'])).toBe('es');
  });
  it('falls back to English', () => {
    expect(resolveLocale('system', ['de-DE', 'ja-JP'])).toBe('en');
    expect(resolveLocale('system', [])).toBe('en');
  });
});
