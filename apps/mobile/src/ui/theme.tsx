import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import { TEXT_SCALE_FACTOR, type AppSettings } from '../core/settings/appSettings';

export interface Colors {
  readonly background: string;
  readonly surface: string;
  readonly text: string;
  readonly mutedText: string;
  readonly primary: string;
  readonly onPrimary: string;
  readonly border: string;
  readonly danger: string;
}

const LIGHT: Colors = {
  background: '#FFFFFF', surface: '#EEF2F8', text: '#1B1B1F', mutedText: '#44474F', primary: '#0B57D0',
  onPrimary: '#FFFFFF', border: '#C4C6D0', danger: '#B3261E',
};
const DARK: Colors = {
  background: '#121316', surface: '#1F2127', text: '#E3E2E6', mutedText: '#C4C6D0', primary: '#A8C7FA',
  onPrimary: '#062E6F', border: '#44474F', danger: '#F2B8B5',
};
// High contrast: maximum contrast pairs, thicker borders, in both colour schemes.
const HC_LIGHT: Colors = {
  background: '#FFFFFF', surface: '#FFFFFF', text: '#000000', mutedText: '#000000', primary: '#00339E',
  onPrimary: '#FFFFFF', border: '#000000', danger: '#8C0009',
};
const HC_DARK: Colors = {
  background: '#000000', surface: '#000000', text: '#FFFFFF', mutedText: '#FFFFFF', primary: '#FFEB3B',
  onPrimary: '#000000', border: '#FFFFFF', danger: '#FFB4AB',
};

export interface Theme {
  readonly colors: Colors;
  readonly highContrast: boolean;
  readonly borderWidth: number;
  /** Scales a font size by the user's text-size setting. */
  fs(size: number): number;
}

const ThemeContext = createContext<Theme | null>(null);

export function makeTheme(settings: Pick<AppSettings, 'textScale' | 'highContrast'>, dark: boolean): Theme {
  const factor = TEXT_SCALE_FACTOR[settings.textScale];
  const colors = settings.highContrast ? (dark ? HC_DARK : HC_LIGHT) : dark ? DARK : LIGHT;
  return {
    colors,
    highContrast: settings.highContrast,
    borderWidth: settings.highContrast ? 2 : 1,
    fs: (size) => Math.round(size * factor),
  };
}

export function ThemeProvider({ settings, children }: { settings: AppSettings; children: ReactNode }) {
  const dark = useColorScheme() === 'dark';
  const theme = useMemo(() => makeTheme(settings, dark), [settings.textScale, settings.highContrast, dark]);
  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  const theme = useContext(ThemeContext);
  if (theme === null) throw new Error('useTheme must be used inside ThemeProvider');
  return theme;
}
