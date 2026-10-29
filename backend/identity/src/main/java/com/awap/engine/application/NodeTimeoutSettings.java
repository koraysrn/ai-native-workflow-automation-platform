package com.awap.engine.application;

import java.time.Duration;

/**
 * Validated bounds of the node timeout feature.
 *
 * <p>Validation happens in the canonical constructor, so a bad configuration (zero, negative or
 * absurd values) fails the application at startup instead of turning every node execution into a
 * timeout.
 *
 * @param defaultSeconds timeout used when a node does not configure {@code timeoutSeconds}
 * @param maxSeconds upper bound accepted for a node {@code timeoutSeconds} value
 * @param workerThreads size of the bounded executor used for interrupt-safe node work
 * @param queueCapacity queue length of that executor before submissions are rejected
 */
public record NodeTimeoutSettings(
    int defaultSeconds, int maxSeconds, int workerThreads, int queueCapacity) {

  /** A timeout below one second is never accepted (risk: constant false failures). */
  public static final int MIN_NODE_TIMEOUT_SECONDS = 1;

  /** Default upper bound: one hour. Longer node budgets belong in the async job model. */
  public static final int DEFAULT_MAX_SECONDS = 3600;

  public static final int MAX_WORKER_THREADS = 64;
  public static final int MAX_QUEUE_CAPACITY = 10_000;

  public NodeTimeoutSettings {
    if (maxSeconds < MIN_NODE_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "awap.engine.node-timeout.max-seconds must be >= "
              + MIN_NODE_TIMEOUT_SECONDS
              + " but was "
              + maxSeconds);
    }
    if (defaultSeconds < MIN_NODE_TIMEOUT_SECONDS || defaultSeconds > maxSeconds) {
      throw new IllegalArgumentException(
          "awap.engine.node-timeout.default-seconds must be between "
              + MIN_NODE_TIMEOUT_SECONDS
              + " and "
              + maxSeconds
              + " but was "
              + defaultSeconds);
    }
    if (workerThreads < 1 || workerThreads > MAX_WORKER_THREADS) {
      throw new IllegalArgumentException(
          "awap.engine.node-timeout.worker-threads must be between 1 and "
              + MAX_WORKER_THREADS
              + " but was "
              + workerThreads);
    }
    if (queueCapacity < 0 || queueCapacity > MAX_QUEUE_CAPACITY) {
      throw new IllegalArgumentException(
          "awap.engine.node-timeout.queue-capacity must be between 0 and "
              + MAX_QUEUE_CAPACITY
              + " but was "
              + queueCapacity);
    }
  }

  public static NodeTimeoutSettings defaults() {
    return new NodeTimeoutSettings(30, DEFAULT_MAX_SECONDS, 8, 64);
  }

  public Duration defaultTimeout() {
    return Duration.ofSeconds(defaultSeconds);
  }

  /** True when the given explicit {@code timeoutSeconds} value is acceptable. */
  public boolean isWithinBounds(long seconds) {
    return seconds >= MIN_NODE_TIMEOUT_SECONDS && seconds <= maxSeconds;
  }
}
