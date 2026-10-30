package com.awap.engine.application;

import com.awap.engine.domain.SagaStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Micrometer instruments for saga compensation.
 *
 * <ul>
 *   <li>{@code awap.saga.steps} — every action/compensation outcome, tagged with {@code outcome}
 *       ({@code succeeded}, {@code compensated}, {@code failed}).
 *   <li>{@code awap.saga.compensation.retries} — compensation attempts that were rescheduled.
 *   <li>{@code awap.saga.duration} — end-to-end saga duration, tagged with the terminal status.
 * </ul>
 */
@Component
public class SagaMetrics {

  public static final String STEPS = "awap.saga.steps";
  public static final String RETRIES = "awap.saga.compensation.retries";
  public static final String DURATION = "awap.saga.duration";

  public static final String OUTCOME_SUCCEEDED = "succeeded";
  public static final String OUTCOME_COMPENSATED = "compensated";
  public static final String OUTCOME_FAILED = "failed";

  private final MeterRegistry meterRegistry;

  public SagaMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordStepSucceeded() {
    meterRegistry.counter(STEPS, "outcome", OUTCOME_SUCCEEDED).increment();
  }

  public void recordStepCompensated() {
    meterRegistry.counter(STEPS, "outcome", OUTCOME_COMPENSATED).increment();
  }

  public void recordStepFailed() {
    meterRegistry.counter(STEPS, "outcome", OUTCOME_FAILED).increment();
  }

  public void recordRetryScheduled() {
    meterRegistry.counter(RETRIES).increment();
  }

  public void recordSagaDuration(Duration elapsed, SagaStatus status) {
    Duration safe = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
    Timer.builder(DURATION)
        .description("End-to-end duration of a saga coordinated by the workflow engine.")
        .tag("status", status == null ? "UNKNOWN" : status.name())
        .register(meterRegistry)
        .record(safe);
  }
}
