package com.awap.engine.domain;

/** State machine states of a workflow instance. */
public enum WorkflowState {
  CREATED,
  STARTED,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED,
  PAUSED
}
