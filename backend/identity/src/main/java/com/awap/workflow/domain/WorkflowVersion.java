package com.awap.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** An immutable snapshot of a workflow definition at a given version. */
@Entity
@Table(name = "workflow_versions")
public class WorkflowVersion {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_id", nullable = false)
  private UUID workflowId;

  @Column(nullable = false)
  private int version;

  @Column(name = "definition_json", nullable = false, columnDefinition = "TEXT")
  private String definitionJson;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private WorkflowStatus status;

  @Column(nullable = false)
  private Instant createdAt;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(nullable = false)
  private boolean deleted;

  protected WorkflowVersion() {}

  public WorkflowVersion(
      UUID tenantId, UUID workflowId, int version, String definitionJson, WorkflowStatus status) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowId = workflowId;
    this.version = version;
    this.definitionJson = definitionJson;
    this.status = status;
    this.createdAt = Instant.now();
    this.deleted = false;
  }

  public void publish() {
    this.status = WorkflowStatus.PUBLISHED;
  }

  public void archive() {
    this.deleted = true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getWorkflowId() {
    return workflowId;
  }

  public int getVersion() {
    return version;
  }

  public String getDefinitionJson() {
    return definitionJson;
  }

  public WorkflowStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isDeleted() {
    return deleted;
  }
}
