import type { RetryPolicy } from './retryPolicy';
import type { SyncResult } from './result';

/**
 * Runs [attempt] until it succeeds, fails for good, or the policy gives up. [sleep] does the waiting (seconds), so
 * a test can pass a recorder instead of a real timer. Returns the last result.
 */
export async function runWithRetry(
  attempt: () => Promise<SyncResult>,
  policy: RetryPolicy,
  sleep: (seconds: number) => Promise<void>,
  shouldStop: () => boolean = () => false,
): Promise<SyncResult> {
  for (let n = 1; ; n++) {
    const result = await attempt();
    const delay = policy.delayBeforeRetry(n, result);
    if (delay === null || shouldStop()) return result;
    await sleep(delay);
    if (shouldStop()) return result;
  }
}
