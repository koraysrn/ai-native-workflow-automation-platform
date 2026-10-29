package com.awap.engine.interfaces.dto;

import com.awap.engine.domain.ReplayDivergence;
import com.awap.engine.domain.ReplayProjection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Projection report returned by a dry-run replay. It is descriptive only and never drives state.
 */
public record ReplayReport(
    UUID instanceId,
    boolean dryRun,
    String result,
    Optional<String> errorCode,
    ReplayStateView projection,
    List<ReplayDivergenceView> divergences,
    int logRows,
    int logPages,
    long durationMs,
    String stateHash) {

  public static ReplayReport from(
      UUID instanceId,
      boolean dryRun,
      ReplayProjection projection,
      List<ReplayDivergence> divergences,
      int logRows,
      int logPages,
      long durationMs) {
    Map<String, String> statuses = new TreeMap<>();
    projection.state().nodeStatuses().forEach((node, status) -> statuses.put(node, status.name()));
    ReplayStateView state =
        new ReplayStateView(
            instanceId,
            projection.state().instanceState().name(),
            projection.state().currentNodeId(),
            statuses,
            projection.state().branchTokens(),
            projection.state().mergedBranches(),
            projection.state().outputSummaries(),
            projection.state().appliedActions());
    boolean diverged = projection.failedClosed() || !divergences.isEmpty();
    List<ReplayDivergenceView> views =
        divergences.stream()
            .map(d -> new ReplayDivergenceView(d.type().name(), d.nodeId(), d.detail()))
            .toList();
    return new ReplayReport(
        instanceId,
        dryRun,
        diverged ? "DIVERGED" : "MATCHED",
        diverged ? Optional.of("AWAP-ENG-0004") : Optional.empty(),
        state,
        views,
        logRows,
        logPages,
        durationMs,
        projection.stateHash());
  }
}
