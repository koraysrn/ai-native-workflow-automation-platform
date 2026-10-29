package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/** A running instance of a published workflow version. */
@Entity
@Table(name = "workflow_instances")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class WorkflowInstance {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_definition_id", nullable = false)
  private UUID workflowDefinitionId;

  @Column(name = "workflow_version", nullable = false)
  private int workflowVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private WorkflowState state;

  @Column(name = "context_json", columnDefinition = "TEXT")
  private String contextJson;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "parent_instance_id")
  private UUID parentInstanceId;

  @Column(name = "parent_node_id")
  private String parentNodeId;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  protected WorkflowInstance() {}

  public WorkflowInstance(
      UUID tenantId,
      UUID workflowDefinitionId,
      int workflowVersion,
      WorkflowState state,
      String idempotencyKey,
      String contextJson) {
    this(
        tenantId,
        workflowDefinitionId,
        workflowVersion,
        state,
        idempotencyKey,
        contextJson,
        null,
        null);
  }

  public WorkflowInstance(
      UUID tenantId,
      UUID workflowDefinitionId,
      int workflowVersion,
      WorkflowState state,
      String idempotencyKey,
      String contextJson,
      UUID parentInstanceId,
      String parentNodeId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowDefinitionId = workflowDefinitionId;
    this.workflowVersion = workflowVersion;
    this.state = state;
    this.idempotencyKey = idempotencyKey;
    this.contextJson = contextJson;
    this.parentInstanceId = parentInstanceId;
    this.parentNodeId = parentNodeId;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
  }

  public void transitionTo(WorkflowState target) {
    if (state == WorkflowState.COMPLETED || state == WorkflowState.CANCELLED) {
      throw new IllegalStateException("Terminal state " + state + " cannot transition.");
    }
    if (state == WorkflowState.FAILED && target != WorkflowState.RUNNING) {
      throw new IllegalStateException("Failed workflow can only transition to RUNNING (retry).");
    }
    this.state = target;
    this.updatedAt = Instant.now();
  }

  public void pause() {
    if (state != WorkflowState.RUNNING && state != WorkflowState.WAITING) {
      throw new IllegalStateException("Only RUNNING or WAITING workflows can be paused.");
    }
    this.state = WorkflowState.PAUSED;
    this.updatedAt = Instant.now();
  }

  public void resume() {
    if (state != WorkflowState.PAUSED) {
      throw new IllegalStateException("Only PAUSED workflows can be resumed.");
    }
    this.state = WorkflowState.RUNNING;
    this.updatedAt = Instant.now();
  }

  /** Records the latest context snapshot, e.g. the pinned child definition version. */
  public void updateContextJson(String contextJson) {
    this.contextJson = contextJson;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getWorkflowDefinitionId() {
    return workflowDefinitionId;
  }

  public int getWorkflowVersion() {
    return workflowVersion;
  }

  public WorkflowState getState() {
    return state;
  }

  public String getContextJson() {
    return contextJson;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getParentInstanceId() {
    return parentInstanceId;
  }

  public String getParentNodeId() {
    return parentNodeId;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public boolean isDeleted() {
    return deleted;
  }
}
