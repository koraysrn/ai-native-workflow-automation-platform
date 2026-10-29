package com.awap.engine.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable projection of a workflow instance rebuilt from its execution log.
 *
 * <p>Every reducer action produces a new state; nothing is mutated in place. Maps are kept in
 * sorted order so {@link #stateHash()} is stable across runs, JVMs and hash seeds.
 */
public record ReplayState(
    UUID instanceId,
    WorkflowState instanceState,
    Optional<String> currentNodeId,
    Map<String, NodeExecutionStatus> nodeStatuses,
    Map<String, Integer> branchTokens,
    Map<String, Integer> mergedBranches,
    Map<String, String> outputSummaries,
    int appliedActions) {

  public ReplayState {
    Objects.requireNonNull(instanceId, "instanceId");
    Objects.requireNonNull(instanceState, "instanceState");
    currentNodeId = currentNodeId == null ? Optional.empty() : currentNodeId;
    nodeStatuses = ReplayImmutables.sortedCopy(nodeStatuses);
    branchTokens = ReplayImmutables.sortedCopy(branchTokens);
    mergedBranches = ReplayImmutables.sortedCopy(mergedBranches);
    outputSummaries = ReplayImmutables.sortedCopy(outputSummaries);
    if (appliedActions < 0) {
      throw new IllegalArgumentException("appliedActions must not be negative");
    }
  }

  public static ReplayState initial(UUID instanceId) {
    return new ReplayState(
        instanceId,
        WorkflowState.CREATED,
        Optional.empty(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        0);
  }

  public Optional<NodeExecutionStatus> nodeStatus(String nodeId) {
    return Optional.ofNullable(nodeStatuses.get(nodeId));
  }

  public ReplayState withInstanceState(WorkflowState newState, Optional<String> current) {
    return copy(newState, current, nodeStatuses, branchTokens, mergedBranches, outputSummaries);
  }

  public ReplayState withNodeStatus(String nodeId, NodeExecutionStatus status) {
    Map<String, NodeExecutionStatus> updated = new TreeMap<>(nodeStatuses);
    updated.put(nodeId, status);
    return copy(
        instanceState, Optional.of(nodeId), updated, branchTokens, mergedBranches, outputSummaries);
  }

  public ReplayState withBranchCounters(String nodeId, int tokens, int merged) {
    Map<String, Integer> newTokens = new TreeMap<>(branchTokens);
    Map<String, Integer> newMerged = new TreeMap<>(mergedBranches);
    newTokens.put(nodeId, tokens);
    newMerged.put(nodeId, merged);
    return copy(instanceState, currentNodeId, nodeStatuses, newTokens, newMerged, outputSummaries);
  }

  public ReplayState withMergedBranches(String nodeId, int merged) {
    Map<String, Integer> newMerged = new TreeMap<>(mergedBranches);
    newMerged.put(nodeId, merged);
    return copy(
        instanceState, currentNodeId, nodeStatuses, branchTokens, newMerged, outputSummaries);
  }

  public ReplayState withOutputSummary(String nodeId, String summary) {
    Map<String, String> updated = new TreeMap<>(outputSummaries);
    updated.put(nodeId, summary);
    return copy(instanceState, currentNodeId, nodeStatuses, branchTokens, mergedBranches, updated);
  }

  public ReplayState withAppliedActions(int applied) {
    return new ReplayState(
        instanceId,
        instanceState,
        currentNodeId,
        nodeStatuses,
        branchTokens,
        mergedBranches,
        outputSummaries,
        applied);
  }

  /** Canonical SHA-256 of the projected state; identical fixtures must produce identical hashes. */
  public String stateHash() {
    StringBuilder canonical = new StringBuilder();
    canonical
        .append("instance=")
        .append(instanceId)
        .append("|state=")
        .append(instanceState)
        .append("|current=")
        .append(currentNodeId.orElse("-"))
        .append("|actions=")
        .append(appliedActions)
        .append("|nodes=")
        .append(nodeStatuses)
        .append("|tokens=")
        .append(branchTokens)
        .append("|merged=")
        .append(mergedBranches)
        .append("|outputs=")
        .append(outputSummaries);
    return sha256(canonical.toString());
  }

  private ReplayState copy(
      WorkflowState newState,
      Optional<String> current,
      Map<String, NodeExecutionStatus> statuses,
      Map<String, Integer> tokens,
      Map<String, Integer> merged,
      Map<String, String> outputs) {
    return new ReplayState(
        instanceId, newState, current, statuses, tokens, merged, outputs, appliedActions);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  /** Package-private helpers for deterministic, unmodifiable map copies. */
  static final class ReplayImmutables {

    private ReplayImmutables() {}

    static <V> Map<String, V> sortedCopy(Map<String, V> source) {
      if (source == null || source.isEmpty()) {
        return Map.of();
      }
      return Collections.unmodifiableMap(new TreeMap<>(source));
    }
  }
}
