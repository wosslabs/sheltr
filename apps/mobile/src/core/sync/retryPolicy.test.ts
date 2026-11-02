import { RetryPolicy } from './retryPolicy';
import type { SyncResult } from './result';

const failed: SyncResult = { kind: 'failed', reason: 'connection reset' };

describe('RetryPolicy', () => {
  it('doubles delays from the base', () => {
    const policy = new RetryPolicy(5, 30, 3600);
    expect([1, 2, 3, 4].map((n) => policy.delayBeforeRetry(n, failed))).toEqual([30, 60, 120, 240]);
  });
  it('caps the delay and does not overflow', () => {
    const policy = new RetryPolicy(100, 30, 100);
    expect(policy.delayBeforeRetry(2, failed)).toBe(60);
    expect(policy.delayBeforeRetry(3, failed)).toBe(100);
    expect(policy.delayBeforeRetry(99, failed)).toBe(100);
  });
  it('stops once attempts are used up', () => {
    const policy = new RetryPolicy(3);
    expect(policy.delayBeforeRetry(2, failed)).toBe(60);
    expect(policy.delayBeforeRetry(3, failed)).toBeNull();
    expect(policy.delayBeforeRetry(4, failed)).toBeNull();
  });
  it('retries only failures', () => {
    const policy = new RetryPolicy();
    expect(policy.delayBeforeRetry(1, { kind: 'rejectedSignature' })).toBeNull();
    expect(policy.delayBeforeRetry(1, { kind: 'upToDate', bundleVersion: 'v' })).toBeNull();
    expect(policy.delayBeforeRetry(1, { kind: 'updated', bundleVersion: 'v', documentsDownloaded: 1 })).toBeNull();
  });
  it('never retries with a single attempt', () => expect(new RetryPolicy(1).delayBeforeRetry(1, failed)).toBeNull());
  it('rejects invalid policies', () => {
    expect(() => new RetryPolicy(0)).toThrow(RangeError);
    expect(() => new RetryPolicy(5, 0)).toThrow(RangeError);
    expect(() => new RetryPolicy(5, 60, 30)).toThrow(RangeError);
    expect(() => new RetryPolicy().delayBeforeRetry(0, failed)).toThrow(RangeError);
  });
});
