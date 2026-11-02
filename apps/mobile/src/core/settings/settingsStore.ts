import type { SqlExecutor } from '../store/db';
import { assertValidSettings, parseSettings, type AppSettings } from './appSettings';

const KEY = 'app_settings';

/** Reads and writes AppSettings in the settings table, which a content update never touches. */
export class SettingsStore {
  constructor(private readonly db: SqlExecutor) {}

  /** Returns the stored settings, or defaults when nothing has been stored. */
  async get(): Promise<AppSettings> {
    const rows = await this.db.all<{ value: string }>('SELECT value FROM settings WHERE key = ?', [KEY]);
    return parseSettings(rows[0]?.value ?? null);
  }

  async put(settings: AppSettings): Promise<void> {
    assertValidSettings(settings);
    await this.db.run('INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)', [KEY, JSON.stringify(settings)]);
  }
}
