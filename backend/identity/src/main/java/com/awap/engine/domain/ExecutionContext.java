package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Runtime variables of a workflow instance, stored as a JSON map. */
@Entity
@Table(name = "execution_contexts")
public class ExecutionContext {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id", nullable = false)
  private UUID workflowInstanceId;

  @Column(name = "variables_json", columnDefinition = "TEXT")
  private String variablesJson;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected ExecutionContext() {}

  public ExecutionContext(UUID tenantId, UUID workflowInstanceId, String variablesJson) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.variablesJson = variablesJson;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void setVariablesJson(String variablesJson) {
    this.variablesJson = variablesJson;
    this.updatedAt = Instant.now();
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

  public String getVariablesJson() {
    return variablesJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
