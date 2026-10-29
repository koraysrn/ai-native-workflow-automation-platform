package com.awap.engine.domain;

/**
 * Lifecycle of a single node execution within a workflow instance.
 *
 * <p>{@code TIMED_OUT} is a terminal failure with its own marker: the node exceeded its configured
 * timeout budget. It is kept distinct from {@code FAILED} so operators can tell a slow node from
 * one that actually returned an error, while retry/recovery treat both as re-drivable.
 */
public enum NodeExecutionStatus {
  PENDING,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  TIMED_OUT,
  SKIPPED;

  /** True when the execution finished unsuccessfully and may be re-driven by retry/recovery. */
  public boolean isFailed() {
    return this == FAILED || this == TIMED_OUT;
  }
}
