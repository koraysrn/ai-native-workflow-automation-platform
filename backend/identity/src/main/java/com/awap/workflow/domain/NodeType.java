package com.awap.workflow.domain;

/** Workflow node types supported by the engine. */
public enum NodeType {
  START,
  END,
  FORM,
  APPROVAL,
  TASK,
  DELAY,
  PARALLEL,
  MERGE,
  AI
}
