import { render } from '@testing-library/react-native';
import type { ReactElement } from 'react';
import { Retriever } from '../../core/retrieval/retriever';
import { DEFAULT_SETTINGS, type AppSettings } from '../../core/settings/appSettings';
import type { SearchHit, StoredChunk, StoredDocument, StoreReader } from '../../core/store/types';
import { AppContext, type AppServices, type SyncOutcome } from '../../platform/AppContext';
import { ThemeProvider } from '../theme';

export const DOCUMENT: StoredDocument = {
  id: 'doc-1', title: 'Prepare', agency: 'UK Government', publishedDate: '2024-02-02',
  licence: 'Open Government Licence v3.0', country: 'GB', hazards: ['FLOOD'],
  sourceUri: 'https://prepare.campaign.gov.uk/', sourceType: 'html', checksum: 'c', fileChecksum: 'f',
};

const chunk = (id: string, content: string, pageNumber: number | null): StoredChunk => ({
  id, documentId: DOCUMENT.id, content, pageNumber, position: 0,
});

export const FLOOD_CHUNK = chunk('doc-1-0', 'Move to higher ground and avoid walking through flood water.', 3);

/** A store with fixed content: documents plus canned search hits keyed by the searched words. */
export class FakeStore implements StoreReader {
  constructor(
    private readonly docs: StoredDocument[] = [DOCUMENT],
    private readonly hits: SearchHit[] = [{ chunk: FLOOD_CHUNK, score: -5 }],
  ) {}
  async bundleVersion() { return 'v1'; }
  async document(id: string) { return this.docs.find((d) => d.id === id) ?? null; }
  async documents() { return this.docs; }
  async chunks() { return []; }
  async search(query: string) { return query.toLowerCase().includes('flood') ? this.hits : []; }
}

export interface TestServicesOptions {
  settings?: Partial<AppSettings>;
  store?: StoreReader;
  lastSyncEpochSeconds?: number | null;
  syncAvailable?: boolean;
  syncNow?: () => Promise<SyncOutcome>;
}

export function makeServices(options: TestServicesOptions = {}) {
  const updates: Partial<AppSettings>[] = [];
  let settings: AppSettings = { ...DEFAULT_SETTINGS, ...options.settings };
  const store = options.store ?? new FakeStore();
  const services: AppServices = {
    store,
    retriever: new Retriever(store),
    get settings() { return settings; },
    lastSyncEpochSeconds: options.lastSyncEpochSeconds ?? null,
    syncAvailable: options.syncAvailable ?? true,
    updateSettings: async (patch) => {
      updates.push(patch);
      settings = { ...settings, ...patch };
    },
    syncNow: options.syncNow ?? (async () => ({ kind: 'unavailable' })),
  };
  return { services, updates };
}

/** Renders [ui] with fake services and the real theme. */
export function renderWithServices(ui: ReactElement, options: TestServicesOptions = {}) {
  const { services, updates } = makeServices(options);
  const result = render(
    <AppContext.Provider value={services}>
      <ThemeProvider settings={services.settings}>{ui}</ThemeProvider>
    </AppContext.Provider>,
  );
  return { ...result, services, updates };
}
