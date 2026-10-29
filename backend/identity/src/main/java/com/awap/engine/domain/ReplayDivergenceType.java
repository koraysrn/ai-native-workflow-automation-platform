package com.awap.engine.domain;

/** Machine-readable reason why a log replay does not reproduce the persisted state. */
public enum ReplayDivergenceType {
  /** A log token outside the reducer dictionary; projection stops immediately. */
  UNKNOWN_ACTION,
  /** A log row that cannot be reduced (missing node id for a node action). */
  MALFORMED_LOG,
  /** A payload that is not valid JSON; recorded as divergence instead of crashing replay. */
  MALFORMED_PAYLOAD,
  /** Two log rows arrived out of deterministic order. */
  OUT_OF_ORDER_LOG,
  /** A webhook/notification action has no recorded result, so replay cannot reuse it. */
  EXTERNAL_RESULT_MISSING,
  /** Projected instance state differs from {@code workflow_instances.state}. */
  INSTANCE_STATE_MISMATCH,
  /** A node was persisted as COMPLETED but the log has no matching completion action. */
  MISSING_NODE_COMPLETION,
  /** A node exists in both projections with different statuses. */
  NODE_STATUS_MISMATCH,
  /** The log mentions a node row that was never persisted. */
  NODE_NOT_PERSISTED,
  /** Parallel branch token counter differs from {@code node_executions.branch_token_count}. */
  BRANCH_TOKEN_MISMATCH,
  /** Merged branch counter differs from {@code node_executions.merged_branch_count}. */
  BRANCH_MERGE_MISMATCH
}
