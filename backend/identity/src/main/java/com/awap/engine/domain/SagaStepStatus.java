package com.awap.engine.domain;

/**
 * Lifecycle of one saga step.
 *
 * <p>A step is recorded only after its action succeeded, so {@code SUCCEEDED} means "this external
 * effect exists and must be undone if a later step fails". {@code COMPENSATING} covers both the
 * first compensation attempt and the backoff window before a retry. {@code DLQ} is terminal: the
 * compensation exhausted its retry budget and was dead-lettered for manual intervention.
 */
public enum SagaStepStatus {
  SUCCEEDED,
  COMPENSATING,
  COMPENSATED,
  DLQ;

  public boolean isCompensated() {
    return this == COMPENSATED;
  }

  public boolean isDeadLettered() {
    return this == DLQ;
  }
}
