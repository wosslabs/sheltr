import { getNetworkStateAsync, NetworkStateType } from 'expo-network';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { Retriever } from '../core/retrieval/retriever';
import { DEFAULT_SETTINGS, type AppSettings } from '../core/settings/appSettings';
import { SettingsStore } from '../core/settings/settingsStore';
import { nextSyncDue } from '../core/settings/syncDue';
import { ContentStore } from '../core/store/contentStore';
import { SyncEngine } from '../core/sync/engine';
import { RetryPolicy } from '../core/sync/retryPolicy';
import { runWithRetry } from '../core/sync/runWithRetry';
import { resolveLocale, translate } from '../i18n';
import { AppContext, type AppServices, type SyncOutcome } from './AppContext';
import { openExpoDatabase } from './expoSqlite';
import { readPublisherConfig } from './publisherConfig';
import { installSampleBundleIfEmpty } from './sampleBundle';

interface Booted {
  readonly store: ContentStore;
  readonly settingsStore: SettingsStore;
  readonly retriever: Retriever;
}

type Boot = { status: 'loading' } | { status: 'error' } | ({ status: 'ready' } & Booted);

const engine = new SyncEngine();
const publisher = readPublisherConfig();

/** Opens the database, installs the sample content on first run, then provides the app's services. */
export function AppProvider({ children }: { children: ReactNode }) {
  const [boot, setBoot] = useState<Boot>({ status: 'loading' });
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_SETTINGS);
  const [lastSync, setLastSync] = useState<number | null>(null);
  const syncing = useRef(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const db = await openExpoDatabase();
        const store = await ContentStore.open(db);
        await installSampleBundleIfEmpty(store);
        const settingsStore = new SettingsStore(db);
        const loaded = await settingsStore.get();
        const last = await store.lastSyncEpochSeconds();
        if (cancelled) return;
        setSettings(loaded);
        setLastSync(last);
        setBoot({ status: 'ready', store, settingsStore, retriever: new Retriever(store) });
      } catch {
        if (!cancelled) setBoot({ status: 'error' });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const runSync = useCallback(
    async (b: Booted): Promise<SyncOutcome> => {
      if (publisher === null) return { kind: 'unavailable' };
      if (syncing.current) return { kind: 'failed', reason: 'a sync is already running' };
      syncing.current = true;
      try {
        const result = await engine.run(publisher.baseUrl, publisher.publicKey, b.store);
        if (result.kind === 'updated' || result.kind === 'upToDate') setLastSync(await b.store.lastSyncEpochSeconds());
        return result;
      } finally {
        syncing.current = false;
      }
    },
    [],
  );

  // Automatic sync: once per launch, when the user's schedule says one is due and the network allows it.
  useEffect(() => {
    if (boot.status !== 'ready' || publisher === null) return;
    let stopped = false;
    (async () => {
      const state = await getNetworkStateAsync().catch(() => null);
      const unmetered = state?.type === NetworkStateType.WIFI || state?.type === NetworkStateType.ETHERNET;
      const now = Math.floor(Date.now() / 1000);
      if (!nextSyncDue(settings, await boot.store.lastSyncEpochSeconds(), now, unmetered)) return;
      await runWithRetry(
        async () => {
          const outcome = await runSync(boot);
          return outcome.kind === 'unavailable' ? { kind: 'failed', reason: 'unavailable' } : outcome;
        },
        new RetryPolicy(),
        (seconds) => new Promise((resolve) => setTimeout(resolve, seconds * 1000)),
        () => stopped,
      );
    })();
    return () => {
      stopped = true;
    };
    // Only when the app becomes ready: later settings changes apply from the next launch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boot.status]);

  const services = useMemo<AppServices | null>(() => {
    if (boot.status !== 'ready') return null;
    return {
      store: boot.store,
      retriever: boot.retriever,
      settings,
      lastSyncEpochSeconds: lastSync,
      syncAvailable: publisher !== null,
      updateSettings: async (patch) => {
        const next = { ...settings, ...patch };
        await boot.settingsStore.put(next);
        setSettings(next);
      },
      syncNow: () => runSync(boot),
    };
  }, [boot, settings, lastSync, runSync]);

  if (boot.status === 'ready' && services !== null) {
    return <AppContext.Provider value={services}>{children}</AppContext.Provider>;
  }
  const locale = resolveLocale('system');
  return (
    <View style={styles.center} accessibilityLiveRegion="polite">
      {boot.status === 'loading' ? (
        <>
          <ActivityIndicator size="large" />
          <Text style={styles.text}>{translate('preparing_offline_content', locale)}</Text>
        </>
      ) : (
        <>
          <Text style={styles.title}>{translate('error_start_title', locale)}</Text>
          <Text style={styles.text}>{translate('error_start_body', locale)}</Text>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12 },
  title: { fontSize: 22, fontWeight: '700', textAlign: 'center' },
  text: { fontSize: 16, textAlign: 'center' },
});
