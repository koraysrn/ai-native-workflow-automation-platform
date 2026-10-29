package com.awap.engine.interfaces.dto;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public, redacted view of a {@code ReplayState}. */
public record ReplayStateView(
    UUID instanceId,
    String instanceState,
    Optional<String> currentNodeId,
    Map<String, String> nodeStatuses,
    Map<String, Integer> branchTokens,
    Map<String, Integer> mergedBranches,
    Map<String, String> outputSummaries,
    int appliedActions) {}
