package com.awap.engine.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable, persistence-free view of one {@link ExecutionLog} row so the reducer can be exercised
 * without a database.
 */
public record ReplayLogEntry(
    UUID id,
    UUID tenantId,
    UUID workflowInstanceId,
    Optional<String> nodeId,
    Optional<String> nodeType,
    String action,
    Optional<String> payload,
    Instant createdAt) {

  public ReplayLogEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(createdAt, "createdAt");
    nodeId = nodeId == null ? Optional.empty() : nodeId;
    nodeType = nodeType == null ? Optional.empty() : nodeType;
    payload = payload == null ? Optional.empty() : payload;
  }

  /** Adapts a persisted log row without exposing the JPA entity to the reducer. */
  public static ReplayLogEntry of(ExecutionLog log) {
    return new ReplayLogEntry(
        log.getId(),
        log.getTenantId(),
        log.getWorkflowInstanceId(),
        Optional.ofNullable(log.getNodeId()),
        Optional.ofNullable(log.getNodeType()),
        log.getAction(),
        Optional.ofNullable(log.getPayload()),
        log.getCreatedAt());
  }
}
