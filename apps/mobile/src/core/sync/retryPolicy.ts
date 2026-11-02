import type { SyncResult } from './result';

/**
 * When to try a failed sync again. Pure: a scheduler asks it for a delay and does the waiting. Delays grow
 * exponentially from baseDelaySeconds (doubling per attempt) and never exceed maxDelaySeconds.
 */
export class RetryPolicy {
  constructor(
    /** Total attempts including the first. */
    readonly maxAttempts: number = 5,
    readonly baseDelaySeconds: number = 30,
    readonly maxDelaySeconds: number = 3600,
  ) {
    if (!(maxAttempts >= 1)) throw new RangeError('maxAttempts must be at least 1');
    if (!(baseDelaySeconds > 0)) throw new RangeError('baseDelaySeconds must be positive');
    if (!(maxDelaySeconds >= baseDelaySeconds)) throw new RangeError('maxDelaySeconds must be at least baseDelaySeconds');
  }

  /**
   * Seconds to wait after failed attempt number failedAttempts (1 for the first attempt) before the next one, or
   * null if no attempt is left. A result that retrying cannot fix (a rejected signature, or success) also returns
   * null.
   */
  delayBeforeRetry(failedAttempts: number, result: SyncResult): number | null {
    if (!(failedAttempts >= 1)) throw new RangeError('failedAttempts starts at 1');
    if (result.kind !== 'failed') return null;
    if (failedAttempts >= this.maxAttempts) return null;
    // base * 2^(n-1), capped.
    return Math.min(this.baseDelaySeconds * 2 ** (failedAttempts - 1), this.maxDelaySeconds);
  }
}
