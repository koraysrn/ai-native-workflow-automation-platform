package com.awap.webhook.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Retry classification and backoff for webhook deliveries.
 *
 * <p>Delays are 1s, 5s, 25s, 2m and 10m with up to 20% jitter. Any further retry (when a node
 * configures a larger attempt budget, capped at 10) reuses the longest delay. 4xx responses are
 * permanent except 408 (request timeout) and 429 (rate limited); 429 honours {@code Retry-After}.
 * Transport failures and 5xx are retryable.
 */
public final class WebhookRetryPolicy {

  public static final int MAX_ATTEMPTS = 10;
  public static final double DEFAULT_JITTER_RATIO = 0.2;

  private static final long[] DEFAULT_DELAYS_MS = {1_000L, 5_000L, 25_000L, 120_000L, 600_000L};

  private final int maxAttempts;
  private final long[] delaysMs;
  private final double jitterRatio;
  private final DoubleSupplier random;

  public WebhookRetryPolicy() {
    this(MAX_ATTEMPTS, DEFAULT_DELAYS_MS, DEFAULT_JITTER_RATIO, Math::random);
  }

  public WebhookRetryPolicy(
      int maxAttempts, long[] delaysMs, double jitterRatio, DoubleSupplier random) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1.");
    }
    if (delaysMs == null || delaysMs.length == 0) {
      throw new IllegalArgumentException("At least one retry delay is required.");
    }
    if (jitterRatio < 0 || jitterRatio > 1) {
      throw new IllegalArgumentException("jitterRatio must be within [0, 1].");
    }
    this.maxAttempts = Math.min(maxAttempts, MAX_ATTEMPTS);
    this.delaysMs = delaysMs.clone();
    this.jitterRatio = jitterRatio;
    this.random = random;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  /** 4xx is permanent except 408 and 429; 3xx/5xx/transport failures stay retryable. */
  public boolean isPermanent(int statusCode) {
    return statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429;
  }

  public boolean isRetryable(int statusCode) {
    return !isPermanent(statusCode);
  }

  /**
   * Base delay for the retry that follows {@code attemptsMade} failed attempts (1-based).
   *
   * @param attemptsMade number of attempts already performed (including the synchronous first one)
   */
  public Duration delayBeforeRetry(int attemptsMade) {
    int index = Math.max(0, Math.min(attemptsMade - 1, delaysMs.length - 1));
    return Duration.ofMillis(delaysMs[index]);
  }

  /** Applies up to {@code jitterRatio} of positive jitter to the base delay. */
  public Duration applyJitter(Duration base) {
    long jittered = base.toMillis() + (long) (base.toMillis() * jitterRatio * random.getAsDouble());
    return Duration.ofMillis(Math.max(1L, jittered));
  }

  /** Parses {@code Retry-After} as delta-seconds or an RFC 1123 HTTP date. */
  public Optional<Instant> parseRetryAfter(String header, Instant now) {
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    String value = header.trim();
    try {
      long seconds = Long.parseLong(value);
      if (seconds < 0) {
        return Optional.empty();
      }
      return Optional.of(now.plusSeconds(seconds));
    } catch (NumberFormatException ignored) {
      // Fall through to the HTTP-date form.
    }
    try {
      Instant parsed = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      return Optional.of(parsed.isBefore(now) ? now : parsed);
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  /**
   * Decides the next move after an attempt.
   *
   * @param result outcome of the attempt that just finished
   * @param attemptsMade total attempts performed so far, including the synchronous first attempt
   */
  public RetryDecision decide(WebhookAttemptResult result, int attemptsMade, Instant now) {
    if (result.success()) {
      return RetryDecision.stop("delivered");
    }
    if (isPermanent(result.statusCode())) {
      return RetryDecision.stop("permanent status " + result.statusCode());
    }
    if (attemptsMade >= maxAttempts) {
      return RetryDecision.stop("attempt budget exhausted after " + attemptsMade);
    }
    Optional<Instant> retryAfter =
        result.statusCode() == 429
            ? parseRetryAfter(result.retryAfterHeader().orElse(null), now)
            : Optional.empty();
    if (retryAfter.isPresent()) {
      return RetryDecision.retryAt(retryAfter.get(), "retry-after");
    }
    Duration delay = applyJitter(delayBeforeRetry(attemptsMade));
    Instant next = now.plus(delay);
    return RetryDecision.retryAt(next, "backoff " + delay.toMillis() + "ms");
  }

  /** Whether another attempt is allowed for a delivery that has already failed {@code attempts}. */
  public boolean hasAttemptsLeft(int attemptsMade) {
    return attemptsMade < maxAttempts;
  }

  /** Retry decision: either stop (dead) or retry at a concrete instant. */
  public record RetryDecision(boolean retry, Optional<Instant> nextAttemptAt, String reason) {

    public static RetryDecision stop(String reason) {
      return new RetryDecision(false, Optional.empty(), reason);
    }

    public static RetryDecision retryAt(Instant instant, String reason) {
      return new RetryDecision(true, Optional.of(instant), reason);
    }
  }
}
