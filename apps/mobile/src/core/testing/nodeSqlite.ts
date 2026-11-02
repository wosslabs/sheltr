import { DatabaseSync, type SQLInputValue } from 'node:sqlite';
import type { SqlDatabase, SqlExecutor, SqlValue } from '../store/db';

/** SqlDatabase over Node's built-in SQLite (FTS5 included). Tests only: the app uses expo-sqlite. */
export class NodeSqliteDatabase implements SqlDatabase {
  private readonly db: DatabaseSync;

  constructor(path = ':memory:') {
    this.db = new DatabaseSync(path);
  }

  async exec(sql: string): Promise<void> {
    this.db.exec(sql);
  }

  async run(sql: string, params: readonly SqlValue[] = []): Promise<void> {
    this.db.prepare(sql).run(...(params as SQLInputValue[]));
  }

  async all<T>(sql: string, params: readonly SqlValue[] = []): Promise<T[]> {
    return this.db.prepare(sql).all(...(params as SQLInputValue[])) as T[];
  }

  async transaction(task: (tx: SqlExecutor) => Promise<void>): Promise<void> {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      await task(this);
      this.db.exec('COMMIT');
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  async close(): Promise<void> {
    this.db.close();
  }
}
