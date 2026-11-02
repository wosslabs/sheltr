export type SqlValue = string | number | null;

/** Runs statements. Both a database and a transaction on it are executors. */
export interface SqlExecutor {
  /** Runs one or more statements without parameters. */
  exec(sql: string): Promise<void>;
  run(sql: string, params?: readonly SqlValue[]): Promise<void>;
  all<T>(sql: string, params?: readonly SqlValue[]): Promise<T[]>;
}

/**
 * The only thing the content store needs from SQLite. The app implements it with expo-sqlite; the tests with
 * Node's built-in SQLite. Both must have FTS5.
 */
export interface SqlDatabase extends SqlExecutor {
  /**
   * Runs [task] in a transaction: commits if it resolves, rolls back everything it did if it throws, and rethrows.
   * Statements inside must go through the executor passed to [task].
   */
  transaction(task: (tx: SqlExecutor) => Promise<void>): Promise<void>;
  close(): Promise<void>;
}
