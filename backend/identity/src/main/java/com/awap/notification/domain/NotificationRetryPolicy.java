package com.awap.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * Backoff policy for notification retries: 1s, 5s, 25s, 2m, 10m with {@code maxAttempts = 5}.
 *
 * <p>The policy is framework-free and clock-agnostic; it never returns a negative delay, so a
 * wall-clock rollback (an NTP correction or a clock that jumps backwards) cannot make {@code
 * next_attempt_at} earlier than the last attempt.
 */
public final class NotificationRetryPolicy {

  public static final long[] DEFAULT_DELAYS_MS = {1_000L, 5_000L, 25_000L, 120_000L, 600_000L};
  public static final int DEFAULT_MAX_ATTEMPTS = 5;

  private final long[] delaysMs;
  private final int maxAttempts;

  public NotificationRetryPolicy(long[] delaysMs, int maxAttempts) {
    if (delaysMs == null || delaysMs.length == 0) {
      throw new IllegalArgumentException("At least one retry delay is required.");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1.");
    }
    this.delaysMs = Arrays.copyOf(delaysMs, delaysMs.length);
    this.maxAttempts = maxAttempts;
  }

  public static NotificationRetryPolicy defaults() {
    return new NotificationRetryPolicy(DEFAULT_DELAYS_MS, DEFAULT_MAX_ATTEMPTS);
  }

  /** Parses a comma-separated millisecond list such as {@code 1000,5000,25000,120000,600000}. */
  public static NotificationRetryPolicy fromCommaSeparated(String spec, int maxAttempts) {
    long[] parsed =
        Arrays.stream(spec.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .mapToLong(Long::parseLong)
            .toArray();
    return new NotificationRetryPolicy(parsed, maxAttempts);
  }

  public int maxAttempts() {
    return maxAttempts;
  }

  public long[] delaysMs() {
    return Arrays.copyOf(delaysMs, delaysMs.length);
  }

  /** Delay before the next attempt, after {@code attempt} failed attempts have been recorded. */
  public Duration delayAfter(int attempt) {
    int index = Math.min(Math.max(attempt, 1), delaysMs.length) - 1;
    return Duration.ofMillis(Math.max(0L, delaysMs[index]));
  }

  public boolean isExhausted(int attempts) {
    return attempts >= maxAttempts;
  }

  /**
   * Next schedule for an attempt that just failed.
   *
   * @param now the current (possibly rolled-back) clock value
   * @param lastAttemptAt when the failed attempt was recorded, may be in the future if the clock
   *     moved backwards
   * @param attempt the number of the attempt that just failed
   */
  public Instant nextAttemptAt(Instant now, Instant lastAttemptAt, int attempt) {
    Instant base = lastAttemptAt != null && lastAttemptAt.isAfter(now) ? lastAttemptAt : now;
    return base.plus(delayAfter(attempt));
  }
}
