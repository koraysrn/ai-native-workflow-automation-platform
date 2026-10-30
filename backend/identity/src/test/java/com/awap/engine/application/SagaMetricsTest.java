package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.domain.SagaStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Meter names, tags and defensive null/negative duration handling of the saga metrics facade. */
class SagaMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SagaMetrics metrics = new SagaMetrics(registry);

  @Test
  void recordsEveryStepOutcomeAndRetry() {
    metrics.recordStepSucceeded();
    metrics.recordStepCompensated();
    metrics.recordStepFailed();
    metrics.recordRetryScheduled();

    assertThat(counter(SagaMetrics.OUTCOME_SUCCEEDED)).isEqualTo(1.0);
    assertThat(counter(SagaMetrics.OUTCOME_COMPENSATED)).isEqualTo(1.0);
    assertThat(counter(SagaMetrics.OUTCOME_FAILED)).isEqualTo(1.0);
    assertThat(registry.get(SagaMetrics.RETRIES).counter().count()).isEqualTo(1.0);
  }

  @Test
  void clampsNullAndNegativeDurationsAndHandlesUnknownStatus() {
    metrics.recordSagaDuration(null, null);
    metrics.recordSagaDuration(Duration.ofSeconds(-5), SagaStatus.FAILED);
    metrics.recordSagaDuration(Duration.ZERO, SagaStatus.COMPENSATED);

    assertThat(registry.get(SagaMetrics.DURATION).tag("status", "UNKNOWN").timer().count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(SagaMetrics.DURATION)
                .tag("status", "FAILED")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS))
        .isZero();
    assertThat(registry.get(SagaMetrics.DURATION).tag("status", "COMPENSATED").timer().count())
        .isEqualTo(1);
  }

  private double counter(String outcome) {
    return registry.get(SagaMetrics.STEPS).tag("outcome", outcome).counter().count();
  }
}
