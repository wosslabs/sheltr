import 'result.dart';

/// When to try a failed sync again.
///
/// A scheduler asks for a delay and does the waiting. Delays grow
/// exponentially from [baseDelaySeconds] (doubling per attempt) and never
/// exceed [maxDelaySeconds].
class RetryPolicy {
  RetryPolicy({
    this.maxAttempts = 5,
    this.baseDelaySeconds = 30,
    this.maxDelaySeconds = 3600,
  }) {
    if (maxAttempts < 1) {
      throw RangeError('maxAttempts must be at least 1');
    }
    if (baseDelaySeconds <= 0) {
      throw RangeError('baseDelaySeconds must be positive');
    }
    if (maxDelaySeconds < baseDelaySeconds) {
      throw RangeError('maxDelaySeconds must be at least baseDelaySeconds');
    }
  }

  /// Total attempts including the first.
  final int maxAttempts;
  final int baseDelaySeconds;
  final int maxDelaySeconds;

  /// Seconds to wait after failed attempt number [failedAttempts] (1 for the
  /// first attempt) before the next one, or null if no attempt is left.
  ///
  /// A result that retrying cannot fix (a rejected signature, or success)
  /// also returns null.
  int? delayBeforeRetry(int failedAttempts, SyncResult result) {
    if (failedAttempts < 1) {
      throw RangeError('failedAttempts starts at 1');
    }
    if (result.kind != 'failed') {
      return null;
    }
    if (failedAttempts >= maxAttempts) {
      return null;
    }
    // base * 2^(n-1), capped. A left shift of 64 or more is 0, so double
    // until the next step would pass the cap.
    var delay = baseDelaySeconds;
    for (var step = 1; step < failedAttempts; step++) {
      if (delay > maxDelaySeconds ~/ 2) {
        return maxDelaySeconds;
      }
      delay *= 2;
    }
    return delay > maxDelaySeconds ? maxDelaySeconds : delay;
  }
}
