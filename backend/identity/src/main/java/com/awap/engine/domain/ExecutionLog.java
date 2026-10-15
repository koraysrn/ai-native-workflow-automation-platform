package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit record of every node execution and state transition. */
@Entity
@Table(name = "execution_logs")
public class ExecutionLog {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id", nullable = false)
  private UUID workflowInstanceId;

  @Column(name = "node_id")
  private String nodeId;

  @Column(name = "node_type")
  private String nodeType;

  @Column(nullable = false)
  private String action;

  @Column(columnDefinition = "TEXT")
  private String payload;

  @Column(nullable = false)
  private Instant createdAt;

  protected ExecutionLog() {}

  public ExecutionLog(
      UUID tenantId,
      UUID workflowInstanceId,
      String nodeId,
      String nodeType,
      String action,
      String payload) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.action = action;
    this.payload = payload;
    this.createdAt = Instant.now();
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

  public String getAction() {
    return action;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
