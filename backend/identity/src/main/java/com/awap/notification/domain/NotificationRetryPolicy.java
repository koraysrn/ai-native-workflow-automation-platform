package com.awap.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * Backoff policy for notification retries: the shipped default is one initial attempt plus three
 * retries with delays 1s, 5s and 25s ({@code maxAttempts = 3}).
 *
 * <p>The policy is framework-free and clock-agnostic; it never returns a negative delay, so a
 * wall-clock rollback (an NTP correction or a clock that jumps backwards) cannot make {@code
 * next_attempt_at} earlier than the last attempt.
 *
 * <p>{@code maxAttempts} is the retry budget after the initial delivery. A failure after the {@code
 * maxAttempts}-th retry exhausts the policy, so the default produces the delay sequence 1s, 5s, 25s
 * and dead-letters the delivery when the fourth provider call also fails.
 */
public final class NotificationRetryPolicy {

  public static final long[] DEFAULT_DELAYS_MS = {1_000L, 5_000L, 25_000L};
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  private final long[] delaysMs;
  private final int maxAttempts;

  public NotificationRetryPolicy(long[] delaysMs, int maxAttempts) {
    if (delaysMs == null || delaysMs.length == 0) {
      throw new IllegalArgumentException("At least one retry delay is required.");
    }
    for (long delayMs : delaysMs) {
      if (delayMs <= 0L) {
        throw new IllegalArgumentException(
            "Retry delays must be strictly positive, but got " + delayMs + " ms.");
      }
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

  /** Parses a comma-separated millisecond list such as {@code 1000,5000,25000}. */
  public static NotificationRetryPolicy fromCommaSeparated(String spec, int maxAttempts) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("Retry delays must not be blank.");
    }
    long[] parsed;
    try {
      parsed =
          Arrays.stream(spec.split(","))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .mapToLong(Long::parseLong)
              .toArray();
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "Retry delays must be a comma-separated list of milliseconds: " + spec, ex);
    }
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
    return Duration.ofMillis(delaysMs[index]);
  }

  /**
   * True when the retry budget is spent: the initial attempt plus {@code maxAttempts} retries have
   * failed, so no further attempt may be scheduled.
   */
  public boolean isExhausted(int failedAttempts) {
    return failedAttempts > maxAttempts;
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
