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

/**
 * Persistent saga state for one workflow instance.
 *
 * <p>The workflow engine is the orchestrator: {@code saga_key} is unique per tenant so a duplicate
 * start returns the existing saga, and the lease columns let the recovery scheduler resume a
 * compensation exactly once even when the process crashes mid-transition.
 */
@Entity
@Table(name = "saga_instances")
public class SagaInstance {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id", nullable = false)
  private UUID workflowInstanceId;

  @Column(name = "saga_key", nullable = false)
  private String sagaKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SagaStatus status;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "next_compensation_at")
  private Instant nextCompensationAt;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "compensation_started_at")
  private Instant compensationStartedAt;

  @Column(name = "compensation_completed_at")
  private Instant compensationCompletedAt;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  protected SagaInstance() {}

  public SagaInstance(UUID tenantId, UUID workflowInstanceId, String sagaKey) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.sagaKey = sagaKey;
    this.status = SagaStatus.RUNNING;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
    this.deleted = false;
  }

  /**
   * Starts reverse compensation; repeated calls keep the original start instant.
   *
   * <p>A {@code COMPLETED} saga may be reopened because a parent SUB_PROCESS can fail after its
   * child finished successfully; in that case the child actions still have to be undone.
   */
  public void beginCompensation(String reason, Instant now) {
    if (status == SagaStatus.RUNNING || status == SagaStatus.COMPLETED) {
      this.status = SagaStatus.COMPENSATING;
      this.compensationStartedAt = now;
      this.nextCompensationAt = now;
    } else if (status != SagaStatus.COMPENSATING) {
      throw new IllegalStateException("Saga " + id + " cannot compensate from " + status + ".");
    }
    this.failureReason = reason;
    this.updatedAt = now;
  }

  /** Reopens a saga whose compensation exhausted its retry budget after operator intervention. */
  public void reopenCompensation(String reason, Instant now) {
    if (status != SagaStatus.FAILED) {
      throw new IllegalStateException("Only FAILED sagas can be reopened.");
    }
    this.status = SagaStatus.COMPENSATING;
    this.failureReason = reason;
    this.nextCompensationAt = now;
    this.compensationCompletedAt = null;
    this.leaseToken = null;
    this.leaseUntil = null;
    this.updatedAt = now;
  }

  /** Schedules the next recovery tick after a compensable step failed retryably. */
  public void scheduleCompensation(Instant next, Instant now) {
    requireCompensating();
    this.nextCompensationAt = next;
    this.updatedAt = now;
  }

  public void claim(UUID token, Instant until, Instant now) {
    requireCompensating();
    this.leaseToken = token;
    this.leaseUntil = until;
    this.updatedAt = now;
  }

  public void releaseLease(Instant now) {
    this.leaseToken = null;
    this.leaseUntil = null;
    this.updatedAt = now;
  }

  public void markCompensated(Instant now) {
    requireCompensating();
    this.status = SagaStatus.COMPENSATED;
    this.compensationCompletedAt = now;
    this.nextCompensationAt = null;
    releaseLease(now);
    this.updatedAt = now;
  }

  public void markFailed(String reason, Instant now) {
    requireCompensating();
    this.status = SagaStatus.FAILED;
    this.failureReason = reason;
    this.compensationCompletedAt = now;
    this.nextCompensationAt = null;
    releaseLease(now);
    this.updatedAt = now;
  }

  /** The workflow finished without any failing step; no compensation is needed. */
  public void markCompleted(Instant now) {
    if (status != SagaStatus.RUNNING) {
      return;
    }
    this.status = SagaStatus.COMPLETED;
    this.compensationCompletedAt = now;
    this.updatedAt = now;
  }

  private void requireCompensating() {
    if (status != SagaStatus.COMPENSATING) {
      throw new IllegalStateException(
          "Saga " + id + " is not compensating (status " + status + ").");
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

  public String getSagaKey() {
    return sagaKey;
  }

  public SagaStatus getStatus() {
    return status;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getNextCompensationAt() {
    return nextCompensationAt;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public UUID getLeaseToken() {
    return leaseToken;
  }

  public Instant getCompensationStartedAt() {
    return compensationStartedAt;
  }

  public Instant getCompensationCompletedAt() {
    return compensationCompletedAt;
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
