package com.awap.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Optional per-tenant daily limit override for a single metric. */
@Entity
@Table(
    name = "tenant_usage_limits",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_tenant_usage_limits",
            columnNames = {"tenant_id", "metric"}))
public class TenantUsageLimit {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "metric", nullable = false, length = 40)
  private String metric;

  @Column(name = "daily_limit", nullable = false)
  private long dailyLimit;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TenantUsageLimit() {}

  public TenantUsageLimit(UUID tenantId, UsageMetric metric, long dailyLimit) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.metric = metric.name();
    this.dailyLimit = dailyLimit;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getMetric() {
    return metric;
  }

  public long getDailyLimit() {
    return dailyLimit;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void updateDailyLimit(long dailyLimit) {
    this.dailyLimit = dailyLimit;
    this.updatedAt = Instant.now();
  }
}
