import * as SQLite from 'expo-sqlite';
import type { SqlDatabase, SqlExecutor, SqlValue } from '../core/store/db';

/** Wraps an expo-sqlite connection (or transaction) as a SqlExecutor. */
function executor(db: SQLite.SQLiteDatabase): SqlExecutor {
  return {
    exec: (sql) => db.execAsync(sql),
    run: async (sql, params = []) => {
      await db.runAsync(sql, params as SqlValue[]);
    },
    all: <T>(sql: string, params: readonly SqlValue[] = []) => db.getAllAsync<T>(sql, params as SqlValue[]),
  };
}

/** SqlDatabase over expo-sqlite. FTS5 is compiled in (config plugin option enableFTS defaults to true). */
export async function openExpoDatabase(name = 'sheltr.db'): Promise<SqlDatabase> {
  const db = await SQLite.openDatabaseAsync(name);
  await db.execAsync('PRAGMA journal_mode = WAL;');
  return {
    ...executor(db),
    // Exclusive: only statements run through the transaction object are part of it, so a concurrent read of the
    // old content cannot leak into the replace, and an error rolls the whole replace back.
    transaction: (task) => db.withExclusiveTransactionAsync(async (txn) => task(executor(txn))),
    close: () => db.closeAsync(),
  };
}
