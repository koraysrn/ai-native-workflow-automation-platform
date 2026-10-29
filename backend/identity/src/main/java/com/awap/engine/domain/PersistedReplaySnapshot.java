package com.awap.engine.domain;

import java.util.Map;
import java.util.Objects;

/** Flattened view of the durable {@code workflow_instances}/{@code node_executions} state. */
public record PersistedReplaySnapshot(
    WorkflowState instanceState,
    Map<String, NodeExecutionStatus> nodeStatuses,
    Map<String, BranchCounters> branchCounters) {

  public PersistedReplaySnapshot {
    Objects.requireNonNull(instanceState, "instanceState");
    nodeStatuses = ReplayState.ReplayImmutables.sortedCopy(nodeStatuses);
    branchCounters = ReplayState.ReplayImmutables.sortedCopy(branchCounters);
  }

  public static PersistedReplaySnapshot empty() {
    return new PersistedReplaySnapshot(WorkflowState.CREATED, Map.of(), Map.of());
  }

  /** Parallel gateway counters persisted on the fork node execution. */
  public record BranchCounters(int tokens, int merged) {}
}
