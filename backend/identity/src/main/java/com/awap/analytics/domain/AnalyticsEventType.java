package com.awap.analytics.domain;

/** Events emitted by the analytics module (ADR-003). */
public enum AnalyticsEventType {
  WORKFLOW_STARTED,
  WORKFLOW_COMPLETED,
  WORKFLOW_FAILED,
  TASK_COMPLETED,
  APPROVAL_COMPLETED,
  SLA_BREACHED
}
