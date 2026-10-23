package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

/** Verifies the 1s/5s/25s + jitter retry schedule and termination. */
class JitteredBackOffTest {

  @Test
  void returnsExpectedIntervalsWithJitterThenStops() {
    JitteredBackOff backOff = new JitteredBackOff();
    BackOffExecution execution = backOff.start();

    assertThat(execution.nextBackOff()).isBetween(1000L, 1200L);
    assertThat(execution.nextBackOff()).isBetween(5000L, 6000L);
    assertThat(execution.nextBackOff()).isBetween(25000L, 30000L);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }
}
