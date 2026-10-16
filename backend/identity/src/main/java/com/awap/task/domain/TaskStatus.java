package com.awap.task.domain;

/** Lifecycle states of a task in Phase 0. */
public enum TaskStatus {
  OPEN,
  ASSIGNED,
  IN_PROGRESS,
  REJECTED,
  APPROVED,
  COMPLETED,
  CANCELLED
}
