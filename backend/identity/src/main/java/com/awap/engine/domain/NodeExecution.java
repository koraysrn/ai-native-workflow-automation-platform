package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Exactly-once record of a node's execution; guarded by a unique instance+node constraint. */
@Entity
@Table(name = "node_executions")
public class NodeExecution {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id", nullable = false)
  private UUID workflowInstanceId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Column(name = "node_type", nullable = false)
  private String nodeType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NodeExecutionStatus status;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Column(columnDefinition = "TEXT")
  private String error;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected NodeExecution() {}

  public NodeExecution(UUID tenantId, UUID workflowInstanceId, String nodeId, String nodeType) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.status = NodeExecutionStatus.PENDING;
    this.attempt = 0;
    this.createdAt = Instant.now();
  }

  public void start() {
    this.status = NodeExecutionStatus.RUNNING;
    this.attempt = this.attempt + 1;
  }

  public void waitForExternal() {
    this.status = NodeExecutionStatus.WAITING;
  }

  public void complete(String outputJson) {
    this.status = NodeExecutionStatus.COMPLETED;
    this.outputJson = outputJson;
    this.completedAt = Instant.now();
  }

  public void fail(String error) {
    this.status = NodeExecutionStatus.FAILED;
    this.error = error;
    this.completedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getWorkflowInstanceId() {
    return workflowInstanceId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getNodeType() {
    return nodeType;
  }

  public NodeExecutionStatus getStatus() {
    return status;
  }

  public int getAttempt() {
    return attempt;
  }

  public String getOutputJson() {
    return outputJson;
  }

  public String getError() {
    return error;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
