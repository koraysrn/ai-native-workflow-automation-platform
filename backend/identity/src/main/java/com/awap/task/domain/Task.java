package com.awap.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/** A tenant-scoped task with optimistic locking, escalation and a guarded state machine. */
@Entity
@Table(name = "tasks")
public class Task {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id")
  private UUID workflowInstanceId;

  @Column(name = "node_execution_id")
  private UUID nodeExecutionId;

  @Column(nullable = false)
  private String title;

  private String description;

  @Column(name = "assignee_id")
  private UUID assigneeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskStatus status;

  @Column(nullable = false)
  private boolean escalated;

  @Column(name = "escalate_at")
  private Instant escalateAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "decision_comment")
  private String decisionComment;

  @Version
  @Column(nullable = false)
  private long version;

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

  protected Task() {}

  public Task(
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      String title,
      String description,
      UUID assigneeId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.nodeExecutionId = nodeExecutionId;
    this.title = title;
    this.description = description;
    this.assigneeId = assigneeId;
    this.status = assigneeId == null ? TaskStatus.OPEN : TaskStatus.ASSIGNED;
    this.escalated = false;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.deleted = false;
  }

  public void assign(UUID assignee) {
    if (status != TaskStatus.OPEN && status != TaskStatus.ASSIGNED) {
      throw new IllegalStateException("Task cannot be assigned in status " + status);
    }
    this.assigneeId = assignee;
    this.status = TaskStatus.ASSIGNED;
    this.updatedAt = Instant.now();
  }

  public void reassign(UUID assignee) {
    if (status == TaskStatus.COMPLETED
        || status == TaskStatus.APPROVED
        || status == TaskStatus.CANCELLED) {
      throw new IllegalStateException("Task cannot be reassigned in status " + status);
    }
    this.assigneeId = assignee;
    this.status = TaskStatus.ASSIGNED;
    this.updatedAt = Instant.now();
  }

  public void claim(UUID user) {
    if (status == TaskStatus.ASSIGNED && assigneeId != null && !assigneeId.equals(user)) {
      throw new IllegalStateException("Task is already assigned to another user.");
    }
    if (status != TaskStatus.OPEN && status != TaskStatus.ASSIGNED) {
      throw new IllegalStateException("Task cannot be claimed in status " + status);
    }
    this.assigneeId = user;
    this.status = TaskStatus.IN_PROGRESS;
    this.updatedAt = Instant.now();
  }

  public void approve() {
    approve(null);
  }

  public void approve(String comment) {
    requireOpenForDecision();
    this.status = TaskStatus.APPROVED;
    this.decisionComment = comment;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void reject() {
    reject(null);
  }

  public void reject(String comment) {
    requireOpenForDecision();
    this.status = TaskStatus.REJECTED;
    this.decisionComment = comment;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void complete() {
    if (status != TaskStatus.IN_PROGRESS && status != TaskStatus.ASSIGNED) {
      throw new IllegalStateException("Task cannot be completed in status " + status);
    }
    this.status = TaskStatus.COMPLETED;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void cancel() {
    this.status = TaskStatus.CANCELLED;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void escalate(UUID assignee, Instant deadline) {
    if (status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED) {
      throw new IllegalStateException("Task cannot be escalated in status " + status);
    }
    this.escalated = true;
    this.escalateAt = deadline;
    this.assigneeId = assignee;
    this.status = TaskStatus.ASSIGNED;
    this.updatedAt = Instant.now();
  }

  public void markEscalationDue() {
    this.escalated = true;
    this.updatedAt = Instant.now();
  }

  private void requireOpenForDecision() {
    if (status != TaskStatus.IN_PROGRESS && status != TaskStatus.ASSIGNED) {
      throw new IllegalStateException("Task cannot be decided in status " + status);
    }
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

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public UUID getAssigneeId() {
    return assigneeId;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public boolean isEscalated() {
    return escalated;
  }

  public Instant getEscalateAt() {
    return escalateAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getDecisionComment() {
    return decisionComment;
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
