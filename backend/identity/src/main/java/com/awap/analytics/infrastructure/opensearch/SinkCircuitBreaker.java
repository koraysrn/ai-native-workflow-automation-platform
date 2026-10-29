package com.awap.analytics.infrastructure.opensearch;

import java.util.function.LongSupplier;

/**
 * Small closed/open/half-open circuit breaker. While open, new events are dropped instead of
 * filling the queue; after the cooldown a single half-open probe decides whether the sink recovers.
 * The nano clock is injectable so tests can advance time without sleeping.
 */
public final class SinkCircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final long openNanos;
  private final LongSupplier nanoClock;

  private State state = State.CLOSED;
  private int consecutiveFailures;
  private long openedAtNanos;

  public SinkCircuitBreaker(int failureThreshold, long openMs, LongSupplier nanoClock) {
    this.failureThreshold = Math.max(1, failureThreshold);
    this.openNanos = Math.max(1L, openMs) * 1_000_000L;
    this.nanoClock = nanoClock;
  }

  public synchronized boolean tryAcquire() {
    long now = nanoClock.getAsLong();
    if (state == State.OPEN) {
      if (now - openedAtNanos >= openNanos) {
        state = State.HALF_OPEN;
        return true;
      }
      return false;
    }
    return state == State.CLOSED;
  }

  public synchronized void recordSuccess() {
    state = State.CLOSED;
    consecutiveFailures = 0;
  }

  public synchronized void recordFailure() {
    consecutiveFailures++;
    if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
      state = State.OPEN;
      openedAtNanos = nanoClock.getAsLong();
    }
  }

  public synchronized State state() {
    return state;
  }

  public synchronized int consecutiveFailures() {
    return consecutiveFailures;
  }
}
