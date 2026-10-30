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
 * One completed action inside a saga plus the compensation that must undo it.
 *
 * <p>{@code idempotencyKey} is sent to external systems on every compensation attempt, so a retry
 * after a crash or timeout cannot perform the rollback twice. The optimistic {@code version} makes
 * concurrent schedulers fail fast instead of silently overwriting each other.
 */
@Entity
@Table(name = "saga_steps")
public class SagaStep {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "saga_instance_id", nullable = false)
  private UUID sagaInstanceId;

  @Column(name = "step_order", nullable = false)
  private int stepOrder;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Column(name = "node_type", nullable = false)
  private String nodeType;

  @Column(name = "action_type", nullable = false)
  private String actionType;

  @Column(name = "action_payload", columnDefinition = "TEXT")
  private String actionPayload;

  @Column(name = "compensation_type", nullable = false)
  private String compensationType;

  @Column(name = "compensation_payload", columnDefinition = "TEXT")
  private String compensationPayload;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SagaStepStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "retry_backoff_ms", nullable = false)
  private long retryBackoffMs;

  @Column(name = "timeout_seconds", nullable = false)
  private int timeoutSeconds;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "compensated_at")
  private Instant compensatedAt;

  protected SagaStep() {}

  public SagaStep(
      UUID tenantId,
      UUID sagaInstanceId,
      int stepOrder,
      String nodeId,
      String nodeType,
      String actionType,
      String actionPayload,
      String compensationType,
      String compensationPayload,
      String idempotencyKey,
      int maxAttempts,
      long retryBackoffMs,
      int timeoutSeconds,
      Instant now) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.sagaInstanceId = sagaInstanceId;
    this.stepOrder = stepOrder;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.actionType = actionType;
    this.actionPayload = actionPayload;
    this.compensationType = compensationType;
    this.compensationPayload = compensationPayload;
    this.idempotencyKey = idempotencyKey;
    this.status = SagaStepStatus.SUCCEEDED;
    this.attempts = 0;
    this.maxAttempts = maxAttempts;
    this.retryBackoffMs = retryBackoffMs;
    this.timeoutSeconds = timeoutSeconds;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void beginCompensation(Instant now) {
    if (status != SagaStepStatus.SUCCEEDED) {
      return;
    }
    this.status = SagaStepStatus.COMPENSATING;
    this.nextAttemptAt = now;
    this.updatedAt = now;
  }

  public void claim(UUID token, Instant until, Instant now) {
    if (status != SagaStepStatus.COMPENSATING) {
      throw new IllegalStateException(
          "Step " + id + " is not compensating (status " + status + ").");
    }
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
    if (status == SagaStepStatus.COMPENSATED) {
      return;
    }
    this.status = SagaStepStatus.COMPENSATED;
    this.compensatedAt = now;
    this.nextAttemptAt = null;
    this.lastError = null;
    releaseLease(now);
  }

  /** Records a failed attempt and schedules the next one inside the budget. */
  public void recordCompensationFailure(String error, Instant nextAttemptAt, Instant now) {
    this.attempts = this.attempts + 1;
    this.status = SagaStepStatus.COMPENSATING;
    this.lastError = error;
    this.nextAttemptAt = nextAttemptAt;
    releaseLease(now);
  }

  /** Exhausts the retry budget and dead-letters the step for manual intervention. */
  public void deadLetter(String error, Instant now) {
    this.attempts = this.attempts + 1;
    this.status = SagaStepStatus.DLQ;
    this.lastError = error;
    this.nextAttemptAt = null;
    releaseLease(now);
  }

  /** Re-arms a dead-lettered step after an operator fixed the external system. */
  public void resetForManualRetry(Instant now) {
    if (status != SagaStepStatus.DLQ) {
      throw new IllegalStateException("Only DLQ steps can be retried manually.");
    }
    this.status = SagaStepStatus.COMPENSATING;
    this.attempts = 0;
    this.lastError = null;
    this.nextAttemptAt = now;
    releaseLease(now);
  }

  public boolean isDue(Instant now) {
    return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getSagaInstanceId() {
    return sagaInstanceId;
  }

  public int getStepOrder() {
    return stepOrder;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getNodeType() {
    return nodeType;
  }

  public String getActionType() {
    return actionType;
  }

  public String getActionPayload() {
    return actionPayload;
  }

  public String getCompensationType() {
    return compensationType;
  }

  public String getCompensationPayload() {
    return compensationPayload;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public SagaStepStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public long getRetryBackoffMs() {
    return retryBackoffMs;
  }

  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public UUID getLeaseToken() {
    return leaseToken;
  }

  public String getLastError() {
    return lastError;
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

  public Instant getCompensatedAt() {
    return compensatedAt;
  }
}
