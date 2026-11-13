package com.awap.workflow.domain;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configurable retry policy for every executable workflow node (issue #210).
 *
 * <p>The policy lives inside a node's {@code config.retryPolicy} block:
 *
 * <pre>{@code
 * "retryPolicy": {
 *   "maxAttempts": 3,
 *   "delaysMs": [1000, 5000, 25000],
 *   "jitterRatio": 0.2,
 *   "retryableErrorClasses": ["TimeoutException", "IOException", "GENERIC_INTERNAL_ERROR"]
 * }
 * }</pre>
 *
 * <p>{@code maxAttempts} is the total attempt budget including the first attempt. A configured
 * {@code retryableErrorClasses} list replaces the default classification: an error is retryable
 * only when its own class simple name, its full class name or its {@link ErrorCode} name appears in
 * the list. Safety-critical errors ({@link ErrorCode#VALIDATION_FAILED}, authorization and invalid
 * definition/version errors) are always permanent, even if listed.
 *
 * <p>The class is framework-free and deterministic for tests: {@link #nextAttemptAt(Instant, int,
 * double)} accepts the jitter roll while production uses {@link #nextAttemptAt(Instant, int)}.
 */
public final class NodeRetryPolicy {

  public static final int DEFAULT_MAX_ATTEMPTS = 3;
  public static final long DEFAULT_BACKOFF_MS = 1_000L;
  private static final int MAX_ATTEMPTS_LIMIT = 20;
  private static final long MAX_DELAY_MS = 3_600_000L;

  /** Never retried, even when a tenant lists them in retryableErrorClasses. */
  private static final Set<ErrorCode> ALWAYS_PERMANENT =
      Set.of(
          ErrorCode.VALIDATION_FAILED,
          ErrorCode.UNAUTHORIZED,
          ErrorCode.FORBIDDEN,
          ErrorCode.WORKFLOW_INVALID_DEFINITION,
          ErrorCode.WORKFLOW_VERSION_INVALID,
          ErrorCode.WORKFLOW_VERSION_NOT_FOUND,
          ErrorCode.WORKFLOW_VERSION_NOT_PUBLISHED,
          ErrorCode.WORKFLOW_VERSION_ARCHIVED,
          ErrorCode.TASK_ACCESS_DENIED);

  private final int maxAttempts;
  private final long[] delaysMs;
  private final double jitterRatio;
  private final Set<String> retryableErrorClasses;

  private NodeRetryPolicy(
      int maxAttempts, long[] delaysMs, double jitterRatio, Set<String> retryableErrorClasses) {
    this.maxAttempts = maxAttempts;
    this.delaysMs = Arrays.copyOf(delaysMs, delaysMs.length);
    this.jitterRatio = jitterRatio;
    this.retryableErrorClasses = Set.copyOf(retryableErrorClasses);
  }

  public static NodeRetryPolicy defaults() {
    return new NodeRetryPolicy(
        DEFAULT_MAX_ATTEMPTS, new long[] {DEFAULT_BACKOFF_MS}, 0.0, Set.of());
  }

  /** Backward-compatible policy built from the legacy top-level maxAttempts/retryBackoffMs. */
  public static NodeRetryPolicy legacy(int maxAttempts, long backoffMs) {
    int attempts = Math.max(1, Math.min(maxAttempts, MAX_ATTEMPTS_LIMIT));
    long delay = Math.max(1L, Math.min(backoffMs, MAX_DELAY_MS));
    return new NodeRetryPolicy(attempts, new long[] {delay}, 0.0, Set.of());
  }

  /**
   * Validates a configured policy without throwing: returns {@code null} when it is valid, or the
   * human-readable reason otherwise. The definition save path turns the reason into a 400 instead
   * of letting an exception escape the transaction.
   */
  public static String validate(JsonNode config) {
    try {
      fromConfig(config);
      return null;
    } catch (IllegalArgumentException ex) {
      return ex.getMessage();
    }
  }

  /**
   * Parses and validates a {@code retryPolicy} object.
   *
   * @throws IllegalArgumentException when the policy is not an object or a value is outside its
   *     documented range; the definition save path maps this to an invalid-definition error
   */
  public static NodeRetryPolicy fromConfig(JsonNode config) {
    if (config == null || !config.isObject()) {
      throw new IllegalArgumentException("retryPolicy must be a JSON object.");
    }
    int maxAttempts = config.path("maxAttempts").asInt(DEFAULT_MAX_ATTEMPTS);
    if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
      throw new IllegalArgumentException(
          "retryPolicy.maxAttempts must be between 1 and " + MAX_ATTEMPTS_LIMIT + ".");
    }
    long[] delays;
    JsonNode delaysNode = config.get("delaysMs");
    if (delaysNode != null && !delaysNode.isNull()) {
      if (!delaysNode.isArray() || delaysNode.isEmpty()) {
        throw new IllegalArgumentException("retryPolicy.delaysMs must be a non-empty array.");
      }
      if (delaysNode.size() > MAX_ATTEMPTS_LIMIT) {
        throw new IllegalArgumentException(
            "retryPolicy.delaysMs must contain at most " + MAX_ATTEMPTS_LIMIT + " entries.");
      }
      List<Long> parsed = new ArrayList<>();
      for (JsonNode delay : delaysNode) {
        if (!delay.isIntegralNumber()) {
          throw new IllegalArgumentException("retryPolicy.delaysMs entries must be integers.");
        }
        long value = delay.asLong();
        if (value < 1 || value > MAX_DELAY_MS) {
          throw new IllegalArgumentException(
              "retryPolicy.delaysMs entries must be between 1 and " + MAX_DELAY_MS + ".");
        }
        parsed.add(value);
      }
      delays = parsed.stream().mapToLong(Long::longValue).toArray();
    } else {
      long backoff = config.path("backoffMs").asLong(DEFAULT_BACKOFF_MS);
      if (backoff < 1 || backoff > MAX_DELAY_MS) {
        throw new IllegalArgumentException(
            "retryPolicy.backoffMs must be between 1 and " + MAX_DELAY_MS + ".");
      }
      delays = new long[] {backoff};
    }
    double jitterRatio = config.path("jitterRatio").asDouble(0.0);
    if (jitterRatio < 0.0 || jitterRatio > 1.0 || Double.isNaN(jitterRatio)) {
      throw new IllegalArgumentException("retryPolicy.jitterRatio must be between 0 and 1.");
    }
    Set<String> retryable = new LinkedHashSet<>();
    JsonNode classes = config.get("retryableErrorClasses");
    if (classes != null && !classes.isNull()) {
      if (!classes.isArray()) {
        throw new IllegalArgumentException(
            "retryPolicy.retryableErrorClasses must be an array of names.");
      }
      for (JsonNode entry : classes) {
        String value = entry.asText("").trim();
        if (value.isEmpty()) {
          throw new IllegalArgumentException(
              "retryPolicy.retryableErrorClasses entries must not be blank.");
        }
        retryable.add(value);
      }
    }
    return new NodeRetryPolicy(maxAttempts, delays, jitterRatio, retryable);
  }

  public int maxAttempts() {
    return maxAttempts;
  }

  public long[] delaysMs() {
    return Arrays.copyOf(delaysMs, delaysMs.length);
  }

  public double jitterRatio() {
    return jitterRatio;
  }

  public Set<String> retryableErrorClasses() {
    return retryableErrorClasses;
  }

  /** True while the total attempt budget still allows another attempt. */
  public boolean allowsAnotherAttempt(int failedAttempts) {
    return failedAttempts < maxAttempts;
  }

  public boolean isExhausted(int failedAttempts) {
    return !allowsAnotherAttempt(failedAttempts);
  }

  /** Base delay before the next attempt after {@code failedAttempt} failures (1-based). */
  public long baseDelayMs(int failedAttempt) {
    int index = Math.min(Math.max(failedAttempt, 1), delaysMs.length) - 1;
    return delaysMs[index];
  }

  /**
   * Jittered next-attempt instant. The jitter roll is in {@code [0,1)}; the delay never drops below
   * one millisecond and never moves the instant before {@code now}.
   */
  public Instant nextAttemptAt(Instant now, int failedAttempt, double jitterRoll) {
    long base = baseDelayMs(failedAttempt);
    double factor = 1.0 + jitterRatio * (2.0 * Math.max(0.0, Math.min(1.0, jitterRoll)) - 1.0);
    long delay = Math.max(1L, Math.round(base * factor));
    return now.plusMillis(delay);
  }

  public Instant nextAttemptAt(Instant now, int failedAttempt) {
    return nextAttemptAt(now, failedAttempt, ThreadLocalRandom.current().nextDouble());
  }

  /**
   * Classifies a failure. Permanent safety errors always win; when a retryable class list is
   * configured, only a matching class or error code is retried.
   */
  public boolean isRetryable(Throwable error) {
    AwapException awap = findCause(error, AwapException.class);
    if (awap != null) {
      ErrorCode code = awap.errorCode();
      if (ALWAYS_PERMANENT.contains(code)) {
        return false;
      }
      if (!retryableErrorClasses.isEmpty()) {
        return retryableErrorClasses.contains(code.name());
      }
      return true;
    }
    if (retryableErrorClasses.isEmpty()) {
      return true;
    }
    for (Throwable current = error; current != null; current = current.getCause()) {
      String simple = current.getClass().getSimpleName();
      String full = current.getClass().getName();
      if (retryableErrorClasses.contains(simple) || retryableErrorClasses.contains(full)) {
        return true;
      }
    }
    return false;
  }

  /** Human-readable summary used in NODE_RETRY_SCHEDULED and NODE_DLQ audit records. */
  public String describe() {
    return "maxAttempts="
        + maxAttempts
        + " delaysMs="
        + Arrays.toString(delaysMs)
        + " jitterRatio="
        + jitterRatio
        + " retryableErrorClasses="
        + retryableErrorClasses.stream().map(name -> name.toUpperCase(Locale.ROOT)).toList();
  }

  private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return type.cast(current);
      }
    }
    return null;
  }
}
