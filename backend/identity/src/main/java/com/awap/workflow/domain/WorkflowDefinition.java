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

/** A versioned workflow template owned by a tenant. */
@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String name;

  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private WorkflowStatus status;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(nullable = false)
  private boolean deleted;

  protected WorkflowDefinition() {}

  public WorkflowDefinition(UUID tenantId, String name, String description) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.name = name;
    this.description = description;
    this.status = WorkflowStatus.DRAFT;
    this.currentVersion = 0;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
  }

  public void publish(int version) {
    this.status = WorkflowStatus.PUBLISHED;
    this.currentVersion = version;
    this.updatedAt = Instant.now();
  }

  public void rollbackTo(int version) {
    this.currentVersion = version;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public WorkflowStatus getStatus() {
    return status;
  }

  public int getCurrentVersion() {
    return currentVersion;
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
