package com.awap.engine.domain;

/** Lifecycle of a saga coordinated by the workflow engine. */
public enum SagaStatus {
  /** Actions are being recorded; nothing has to be compensated yet. */
  RUNNING,
  /** Every action finished successfully and no compensation is required. */
  COMPLETED,
  /** A failure was detected and successful steps are being compensated in reverse order. */
  COMPENSATING,
  /** Every successful step was compensated. */
  COMPENSATED,
  /** A compensation exhausted its retry budget and needs operator intervention. */
  FAILED;

  public boolean isTerminal() {
    return this == COMPLETED || this == COMPENSATED || this == FAILED;
  }
}
