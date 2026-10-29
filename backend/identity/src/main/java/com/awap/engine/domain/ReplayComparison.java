package com.awap.engine.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compares a log projection with the persisted engine state and reports every difference. */
public final class ReplayComparison {

  private ReplayComparison() {}

  public static List<ReplayDivergence> compare(
      ReplayState projected, PersistedReplaySnapshot persisted) {
    List<ReplayDivergence> divergences = new ArrayList<>();

    if (projected.instanceState() != persisted.instanceState()) {
      divergences.add(
          ReplayDivergence.of(
              ReplayDivergenceType.INSTANCE_STATE_MISMATCH,
              null,
              "projected="
                  + projected.instanceState()
                  + " persisted="
                  + persisted.instanceState()));
    }

    for (Map.Entry<String, NodeExecutionStatus> entry : persisted.nodeStatuses().entrySet()) {
      Optional<NodeExecutionStatus> projectedStatus = projected.nodeStatus(entry.getKey());
      if (entry.getValue() == NodeExecutionStatus.COMPLETED) {
        // A persisted completion without a completion action (or with only an earlier WAITING
        // action) means the log is incomplete: replay cannot reproduce the terminal state.
        if (projectedStatus.isEmpty() || projectedStatus.get() != NodeExecutionStatus.COMPLETED) {
          divergences.add(
              ReplayDivergence.of(
                  ReplayDivergenceType.MISSING_NODE_COMPLETION,
                  entry.getKey(),
                  projectedStatus
                      .map(status -> "persisted COMPLETED, last projected status " + status)
                      .orElse("persisted COMPLETED but the log has no action for this node")));
        }
        continue;
      }
      if (projectedStatus.isEmpty()) {
        if (entry.getValue() != NodeExecutionStatus.PENDING) {
          divergences.add(
              ReplayDivergence.of(
                  ReplayDivergenceType.NODE_STATUS_MISMATCH,
                  entry.getKey(),
                  "projected=<absent> persisted=" + entry.getValue()));
        }
        continue;
      }
      if (projectedStatus.get() != entry.getValue()) {
        divergences.add(
            ReplayDivergence.of(
                ReplayDivergenceType.NODE_STATUS_MISMATCH,
                entry.getKey(),
                "projected=" + projectedStatus.get() + " persisted=" + entry.getValue()));
      }
    }

    for (String nodeId : projected.nodeStatuses().keySet()) {
      if (!persisted.nodeStatuses().containsKey(nodeId)) {
        divergences.add(
            ReplayDivergence.of(
                ReplayDivergenceType.NODE_NOT_PERSISTED,
                nodeId,
                "log mentions a node without a persisted execution"));
      }
    }

    for (Map.Entry<String, PersistedReplaySnapshot.BranchCounters> entry :
        persisted.branchCounters().entrySet()) {
      int tokens = projected.branchTokens().getOrDefault(entry.getKey(), 0);
      int merged = projected.mergedBranches().getOrDefault(entry.getKey(), 0);
      if (tokens != entry.getValue().tokens()) {
        divergences.add(
            ReplayDivergence.of(
                ReplayDivergenceType.BRANCH_TOKEN_MISMATCH,
                entry.getKey(),
                "projected=" + tokens + " persisted=" + entry.getValue().tokens()));
      }
      if (merged != entry.getValue().merged()) {
        divergences.add(
            ReplayDivergence.of(
                ReplayDivergenceType.BRANCH_MERGE_MISMATCH,
                entry.getKey(),
                "projected=" + merged + " persisted=" + entry.getValue().merged()));
      }
    }

    return List.copyOf(divergences);
  }
}
