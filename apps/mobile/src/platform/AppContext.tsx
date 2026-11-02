import { createContext, useContext } from 'react';
import type { AppSettings } from '../core/settings/appSettings';
import type { Retriever } from '../core/retrieval/retriever';
import type { SyncResult } from '../core/sync/result';
import type { StoreReader } from '../core/store/types';

/** What a manual sync reports back to the Settings screen. 'unavailable': no bundle host is configured. */
export type SyncOutcome = SyncResult | { readonly kind: 'unavailable' };

/** Everything the screens need from the app's data layer. Tests provide a fake; AppProvider provides the real one. */
export interface AppServices {
  readonly store: StoreReader;
  readonly retriever: Retriever;
  readonly settings: AppSettings;
  updateSettings(patch: Partial<AppSettings>): Promise<void>;
  /** UTC epoch seconds of the last successful sync, or null. */
  readonly lastSyncEpochSeconds: number | null;
  /** True when a bundle host and public key are configured at build time. */
  readonly syncAvailable: boolean;
  syncNow(): Promise<SyncOutcome>;
}

export const AppContext = createContext<AppServices | null>(null);

export function useServices(): AppServices {
  const services = useContext(AppContext);
  if (services === null) throw new Error('useServices must be used inside an AppContext provider');
  return services;
}
