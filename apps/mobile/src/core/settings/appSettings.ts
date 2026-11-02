/** How often an automatic sync runs. */
export const SYNC_INTERVAL_SECONDS = {
  MANUAL: null,
  DAILY: 86_400,
  WEEKLY: 604_800,
  /** 30 days, not a calendar month. */
  MONTHLY: 2_592_000,
} as const;
export type SyncFrequency = keyof typeof SYNC_INTERVAL_SECONDS;
export const SYNC_FREQUENCIES = Object.keys(SYNC_INTERVAL_SECONDS) as SyncFrequency[];

export type TextScale = 'NORMAL' | 'LARGE' | 'EXTRA_LARGE';
export const TEXT_SCALES: TextScale[] = ['NORMAL', 'LARGE', 'EXTRA_LARGE'];

/** Display language of the menus. 'system' follows the device. */
export type Language = 'system' | 'en' | 'es' | 'fr';
export const LANGUAGES: Language[] = ['system', 'en', 'es', 'fr'];

/** User settings. Immutable: change a value by spreading into a new object. */
export interface AppSettings {
  readonly syncFrequency: SyncFrequency;
  /** Sync on metered connections too. Off by default: sync runs only on unmetered networks. */
  readonly allowMobileData: boolean;
  /** ISO 3166-1 alpha-2 code in uppercase ASCII, or null when the user has not chosen one. */
  readonly country: string | null;
  readonly textScale: TextScale;
  readonly highContrast: boolean;
  readonly language: Language;
}

export const DEFAULT_SETTINGS: AppSettings = {
  syncFrequency: 'WEEKLY',
  allowMobileData: false,
  country: null,
  textScale: 'NORMAL',
  highContrast: false,
  language: 'system',
};

export function isValidCountry(code: string): boolean {
  return /^[A-Z]{2}$/.test(code);
}

/** Factor applied to every font size. */
export const TEXT_SCALE_FACTOR: Record<TextScale, number> = { NORMAL: 1, LARGE: 1.2, EXTRA_LARGE: 1.4 };

/**
 * Reads settings from stored JSON, falling back to the default for any missing or invalid field. Never throws:
 * a damaged settings row must not stop the app from starting.
 */
export function parseSettings(json: string | null): AppSettings {
  if (json === null) return DEFAULT_SETTINGS;
  let raw: Record<string, unknown>;
  try {
    const value: unknown = JSON.parse(json);
    if (typeof value !== 'object' || value === null) return DEFAULT_SETTINGS;
    raw = value as Record<string, unknown>;
  } catch {
    return DEFAULT_SETTINGS;
  }
  const d = DEFAULT_SETTINGS;
  const oneOf = <T>(allowed: readonly T[], value: unknown, fallback: T): T =>
    allowed.includes(value as T) ? (value as T) : fallback;
  return {
    syncFrequency: oneOf(SYNC_FREQUENCIES, raw.syncFrequency, d.syncFrequency),
    allowMobileData: typeof raw.allowMobileData === 'boolean' ? raw.allowMobileData : d.allowMobileData,
    country: typeof raw.country === 'string' && isValidCountry(raw.country) ? raw.country : null,
    textScale: oneOf(TEXT_SCALES, raw.textScale, d.textScale),
    highContrast: typeof raw.highContrast === 'boolean' ? raw.highContrast : d.highContrast,
    language: oneOf(LANGUAGES, raw.language, d.language),
  };
}

/** Throws unless settings are valid. Call before storing. */
export function assertValidSettings(settings: AppSettings): void {
  if (settings.country !== null && !isValidCountry(settings.country)) {
    throw new RangeError('country must be null or two uppercase ASCII letters');
  }
}
