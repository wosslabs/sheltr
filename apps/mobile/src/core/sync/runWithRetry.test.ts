import { RetryPolicy } from './retryPolicy';
import type { SyncResult } from './result';
import { runWithRetry } from './runWithRetry';

const failed: SyncResult = { kind: 'failed', reason: 'offline' };
const updated: SyncResult = { kind: 'updated', bundleVersion: 'v', documentsDownloaded: 1 };

describe('runWithRetry', () => {
  it('retries failures with the policy delays and returns the first success', async () => {
    const results = [failed, failed, updated];
    const sleeps: number[] = [];
    const out = await runWithRetry(async () => results.shift()!, new RetryPolicy(5, 30, 3600), async (s) => { sleeps.push(s); });
    expect(out).toEqual(updated);
    expect(sleeps).toEqual([30, 60]);
  });
  it('gives up after the last attempt', async () => {
    let calls = 0;
    const out = await runWithRetry(async () => { calls++; return failed; }, new RetryPolicy(3, 1, 10), async () => {});
    expect(out).toEqual(failed);
    expect(calls).toBe(3);
  });
  it('does not retry a rejected signature', async () => {
    let calls = 0;
    const out = await runWithRetry(async () => { calls++; return { kind: 'rejectedSignature' }; }, new RetryPolicy(), async () => {});
    expect(out).toEqual({ kind: 'rejectedSignature' });
    expect(calls).toBe(1);
  });
  it('stops when asked, even mid-wait', async () => {
    let calls = 0;
    let stop = false;
    const out = await runWithRetry(async () => { calls++; return failed; }, new RetryPolicy(5, 1, 10), async () => { stop = true; }, () => stop);
    expect(out).toEqual(failed);
    expect(calls).toBe(1);
  });
});
