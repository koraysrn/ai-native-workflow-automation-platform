package com.awap.engine.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared deterministic execution-log fixtures for the replay tests. */
public final class ReplayTestFixtures {

  public static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  public static final UUID INSTANCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  private ReplayTestFixtures() {}

  public static ReplayLogEntry entry(
      int sequence, String action, String nodeId, String nodeType, String payload) {
    return entry(
        new UUID(0L, sequence), BASE.plusMillis(sequence), action, nodeId, nodeType, payload);
  }

  public static ReplayLogEntry entry(
      UUID id, Instant createdAt, String action, String nodeId, String nodeType, String payload) {
    return new ReplayLogEntry(
        id,
        TENANT,
        INSTANCE,
        Optional.ofNullable(nodeId),
        Optional.ofNullable(nodeType),
        action,
        Optional.ofNullable(payload),
        createdAt);
  }

  /** START -> FORM -> APPROVAL -> END sequence as the engine writes it today. */
  public static List<ReplayLogEntry> happyPath() {
    return List.of(
        entry(1, "INSTANCE_STARTED", null, null, "Workflow instance started."),
        entry(2, "NODE_COMPLETED", "start", "START", null),
        entry(3, "NODE_WAITING", "form", "FORM", null),
        entry(4, "NODE_COMPLETED", "form", "FORM", "{\"answer\":\"yes\",\"ssn\":\"123-45-6789\"}"),
        entry(5, "NODE_WAITING", "approval", "APPROVAL", null),
        entry(6, "NODE_COMPLETED", "approval", "APPROVAL", "{\"approved\":true}"),
        entry(7, "NODE_COMPLETED", "end", "END", null),
        entry(8, "WORKFLOW_COMPLETED", null, null, "State advanced to COMPLETED"));
  }

  /** Parallel gateway with two branches merging into one join. */
  public static List<ReplayLogEntry> parallelPath() {
    return List.of(
        entry(1, "INSTANCE_STARTED", null, null, "Workflow instance started."),
        entry(2, "NODE_FORKED", "fork", "PARALLEL", "branches=2"),
        entry(3, "NODE_COMPLETED", "branch-b", "TASK", null),
        entry(4, "NODE_MERGED", "fork", "PARALLEL", "merge=join;merged=1;tokens=2"),
        entry(5, "NODE_COMPLETED", "branch-a", "TASK", null),
        entry(6, "NODE_MERGED", "fork", "PARALLEL", "merge=join;merged=2;tokens=2"),
        entry(7, "NODE_COMPLETED", "join", "MERGE", null),
        entry(8, "WORKFLOW_COMPLETED", null, null, "State advanced to COMPLETED"));
  }

  /** Snapshot matching {@link #happyPath()}. */
  public static PersistedReplaySnapshot happyPathSnapshot() {
    return new PersistedReplaySnapshot(
        WorkflowState.COMPLETED,
        java.util.Map.of(
            "start", NodeExecutionStatus.COMPLETED,
            "form", NodeExecutionStatus.COMPLETED,
            "approval", NodeExecutionStatus.COMPLETED,
            "end", NodeExecutionStatus.COMPLETED),
        java.util.Map.of());
  }
}
