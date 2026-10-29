package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit row for one deterministic replay attempt. */
@Entity
@Table(name = "replay_runs")
public class ReplayRun {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "workflow_instance_id", nullable = false)
  private UUID workflowInstanceId;

  @Column(name = "dry_run", nullable = false)
  private boolean dryRun;

  @Column(nullable = false)
  private String result;

  @Column(nullable = false)
  private boolean divergence;

  @Column(name = "divergence_count", nullable = false)
  private int divergenceCount;

  @Column(name = "divergence_detail", columnDefinition = "TEXT")
  private String divergenceDetail;

  @Column(name = "state_hash", nullable = false)
  private String stateHash;

  @Column(name = "log_rows", nullable = false)
  private int logRows;

  @Column(name = "log_pages", nullable = false)
  private int logPages;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "requested_by")
  private UUID requestedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ReplayRun() {}

  public ReplayRun(
      UUID tenantId,
      UUID workflowInstanceId,
      boolean dryRun,
      String result,
      boolean divergence,
      int divergenceCount,
      String divergenceDetail,
      String stateHash,
      int logRows,
      int logPages,
      long durationMs,
      UUID requestedBy) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.workflowInstanceId = workflowInstanceId;
    this.dryRun = dryRun;
    this.result = result;
    this.divergence = divergence;
    this.divergenceCount = divergenceCount;
    this.divergenceDetail = divergenceDetail;
    this.stateHash = stateHash;
    this.logRows = logRows;
    this.logPages = logPages;
    this.durationMs = durationMs;
    this.requestedBy = requestedBy;
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

  public boolean isDryRun() {
    return dryRun;
  }

  public String getResult() {
    return result;
  }

  public boolean isDivergence() {
    return divergence;
  }

  public int getDivergenceCount() {
    return divergenceCount;
  }

  public String getDivergenceDetail() {
    return divergenceDetail;
  }

  public String getStateHash() {
    return stateHash;
  }

  public int getLogRows() {
    return logRows;
  }

  public int getLogPages() {
    return logPages;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public UUID getRequestedBy() {
    return requestedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
