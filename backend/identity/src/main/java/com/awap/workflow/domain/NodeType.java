package com.awap.workflow.domain;

/**
 * Workflow node types supported by the engine.
 *
 * <p>{@code PARALLEL} / {@code MERGE} are kept as aliases of the explicit gateway types so already
 * published workflow versions keep executing after the gateway naming was aligned with the
 * specification.
 */
public enum NodeType {
  START,
  END,
  FORM,
  APPROVAL,
  TASK,
  DELAY,
  PARALLEL,
  PARALLEL_GATEWAY,
  MERGE,
  MERGE_GATEWAY,
  DECISION,
  CONDITION,
  WEBHOOK,
  SUB_PROCESS,
  AI,
  SCRIPT;

  public boolean isParallelGateway() {
    return this == PARALLEL || this == PARALLEL_GATEWAY;
  }

  public boolean isMergeGateway() {
    return this == MERGE || this == MERGE_GATEWAY;
  }

  /** External-call nodes must never be invoked again while replaying recorded state. */
  public boolean isExternalCall() {
    return this == WEBHOOK;
  }
}
