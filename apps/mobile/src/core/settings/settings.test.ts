import { DEFAULT_SETTINGS, SYNC_INTERVAL_SECONDS, assertValidSettings, parseSettings, type AppSettings } from './appSettings';
import { nextSyncDue } from './syncDue';
import { SettingsStore } from './settingsStore';
import { NodeSqliteDatabase } from '../testing/nodeSqlite';
import { SCHEMA } from '../store/schema';

describe('AppSettings', () => {
  it('defaults to weekly, mobile data off, no country, normal text', () => {
    expect(DEFAULT_SETTINGS).toMatchObject({
      syncFrequency: 'WEEKLY', allowMobileData: false, country: null, textScale: 'NORMAL', highContrast: false,
      language: 'system',
    });
  });
  it('accepts two uppercase letters and null', () => {
    expect(() => assertValidSettings({ ...DEFAULT_SETTINGS, country: 'GB' })).not.toThrow();
    expect(() => assertValidSettings({ ...DEFAULT_SETTINGS, country: null })).not.toThrow();
  });
  it('rejects other country values', () => {
    for (const bad of ['gb', 'GBR', '1A', '', 'G', 'G-', 'Gb', 'ÉS', 'GB\u0000']) {
      expect(() => assertValidSettings({ ...DEFAULT_SETTINGS, country: bad })).toThrow(RangeError);
    }
  });
  it('has the specified intervals', () => {
    expect(SYNC_INTERVAL_SECONDS.MANUAL).toBeNull();
    expect(SYNC_INTERVAL_SECONDS.DAILY).toBe(86_400);
    expect(SYNC_INTERVAL_SECONDS.WEEKLY).toBe(604_800);
    expect(SYNC_INTERVAL_SECONDS.MONTHLY).toBe(30 * 86_400);
  });
  it('falls back to defaults for damaged or partial stored settings', () => {
    expect(parseSettings(null)).toEqual(DEFAULT_SETTINGS);
    expect(parseSettings('not json')).toEqual(DEFAULT_SETTINGS);
    expect(parseSettings('[1]')).toEqual(DEFAULT_SETTINGS);
    expect(parseSettings('{"country":"gb","textScale":"HUGE","highContrast":true}')).toEqual({
      ...DEFAULT_SETTINGS, highContrast: true,
    });
  });
});

describe('SettingsStore', () => {
  it('returns what was put and survives a content-table wipe', async () => {
    const db = new NodeSqliteDatabase();
    await db.exec(SCHEMA);
    const store = new SettingsStore(db);
    expect(await store.get()).toEqual(DEFAULT_SETTINGS);
    const changed: AppSettings = {
      syncFrequency: 'MONTHLY', allowMobileData: true, country: 'NZ', textScale: 'EXTRA_LARGE', highContrast: true,
      language: 'fr',
    };
    await store.put(changed);
    await db.exec('DELETE FROM documents');
    expect(await store.get()).toEqual(changed);
  });
  it('refuses to store an invalid country', async () => {
    const db = new NodeSqliteDatabase();
    await db.exec(SCHEMA);
    await expect(new SettingsStore(db).put({ ...DEFAULT_SETTINGS, country: 'xx' })).rejects.toThrow(RangeError);
  });
});

describe('nextSyncDue', () => {
  const now = 10_000_000;
  const settings = (syncFrequency: AppSettings['syncFrequency'], allowMobileData = false): AppSettings => ({
    ...DEFAULT_SETTINGS, syncFrequency, allowMobileData,
  });

  it('never auto-syncs when manual', () => {
    expect(nextSyncDue(settings('MANUAL', true), null, now, true)).toBe(false);
    expect(nextSyncDue(settings('MANUAL', true), 0, now, true)).toBe(false);
  });
  it('is due when never synced on an unmetered network', () =>
    expect(nextSyncDue(settings('WEEKLY'), null, now, true)).toBe(true));
  it('is blocked on metered networks unless mobile data is allowed', () => {
    expect(nextSyncDue(settings('WEEKLY'), null, now, false)).toBe(false);
    expect(nextSyncDue(settings('WEEKLY', true), null, now, false)).toBe(true);
    expect(nextSyncDue(settings('WEEKLY'), now - 10 * 604_800, now, false)).toBe(false);
  });
  it('is due exactly at the interval, not a second before', () => {
    expect(nextSyncDue(settings('DAILY'), now - 86_400, now, true)).toBe(true);
    expect(nextSyncDue(settings('DAILY'), now - 86_400 + 1, now, true)).toBe(false);
    expect(nextSyncDue(settings('WEEKLY'), now - 604_800, now, true)).toBe(true);
    expect(nextSyncDue(settings('WEEKLY'), now - 604_799, now, true)).toBe(false);
  });
  it('uses 30 days for monthly', () => {
    const thirty = 30 * 86_400;
    expect(nextSyncDue(settings('MONTHLY'), now - thirty, now, true)).toBe(true);
    expect(nextSyncDue(settings('MONTHLY'), now - thirty + 1, now, true)).toBe(false);
  });
  it('is not due for a last sync in the future', () =>
    expect(nextSyncDue(settings('DAILY'), now + 500, now, true)).toBe(false));
});
