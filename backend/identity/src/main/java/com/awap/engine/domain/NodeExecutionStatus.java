package com.awap.engine.domain;

/** Lifecycle of a single node execution within a workflow instance. */
public enum NodeExecutionStatus {
  PENDING,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  SKIPPED
}
