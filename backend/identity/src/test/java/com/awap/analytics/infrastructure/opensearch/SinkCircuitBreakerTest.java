package com.awap.analytics.infrastructure.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SinkCircuitBreakerTest {

  private final AtomicLong now = new AtomicLong();

  private SinkCircuitBreaker breaker() {
    return new SinkCircuitBreaker(2, 1_000L, now::get);
  }

  @Test
  void opensAfterConsecutiveFailuresAndRejectsWhileOpen() {
    SinkCircuitBreaker breaker = breaker();

    assertThat(breaker.tryAcquire()).isTrue();
    breaker.recordFailure();
    assertThat(breaker.state()).isEqualTo(SinkCircuitBreaker.State.CLOSED);

    breaker.recordFailure();
    assertThat(breaker.state()).isEqualTo(SinkCircuitBreaker.State.OPEN);
    assertThat(breaker.tryAcquire()).isFalse();
  }

  @Test
  void halfOpenProbeClosesOnSuccess() {
    SinkCircuitBreaker breaker = breaker();
    breaker.recordFailure();
    breaker.recordFailure();
    now.addAndGet(1_000_000_000L);

    assertThat(breaker.tryAcquire()).isTrue();
    assertThat(breaker.state()).isEqualTo(SinkCircuitBreaker.State.HALF_OPEN);
    assertThat(breaker.tryAcquire()).isFalse();

    breaker.recordSuccess();
    assertThat(breaker.state()).isEqualTo(SinkCircuitBreaker.State.CLOSED);
    assertThat(breaker.consecutiveFailures()).isZero();
  }

  @Test
  void halfOpenFailureReopensTheCircuit() {
    SinkCircuitBreaker breaker = breaker();
    breaker.recordFailure();
    breaker.recordFailure();
    now.addAndGet(1_000_000_000L);
    assertThat(breaker.tryAcquire()).isTrue();

    breaker.recordFailure();

    assertThat(breaker.state()).isEqualTo(SinkCircuitBreaker.State.OPEN);
    assertThat(breaker.tryAcquire()).isFalse();
  }
}
