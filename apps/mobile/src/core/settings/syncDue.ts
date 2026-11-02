import { SYNC_INTERVAL_SECONDS, type AppSettings } from './appSettings';

/**
 * Whether an automatic sync should start now. Pure: the caller supplies the clock and the network state.
 *
 * - Manual sync is never automatic.
 * - A sync is allowed only on an unmetered network, unless allowMobileData is true.
 * - If the app has never synced, a sync is due as soon as one is allowed.
 * - Otherwise it is due once nowEpochSeconds is at least the interval after lastSyncEpochSeconds.
 */
export function nextSyncDue(
  settings: AppSettings,
  lastSyncEpochSeconds: number | null,
  nowEpochSeconds: number,
  onUnmeteredNetwork: boolean,
): boolean {
  const interval = SYNC_INTERVAL_SECONDS[settings.syncFrequency];
  if (interval === null) return false;
  if (!onUnmeteredNetwork && !settings.allowMobileData) return false;
  if (lastSyncEpochSeconds === null) return true;
  return nowEpochSeconds - lastSyncEpochSeconds >= interval;
}
