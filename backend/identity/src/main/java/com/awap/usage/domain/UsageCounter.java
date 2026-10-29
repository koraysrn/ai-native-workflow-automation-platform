package com.awap.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Tenant/day/metric counter. Writes always go through {@code awap_usage_record_event} so the
 * increment is atomic and idempotent; this entity backs the reported read model.
 */
@Entity
@Table(
    name = "usage_counters",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_usage_counters",
            columnNames = {"tenant_id", "day", "metric"}))
public class UsageCounter {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "day", nullable = false)
  private LocalDate day;

  @Column(name = "metric", nullable = false, length = 40)
  private String metric;

  @Column(name = "value", nullable = false)
  private long value;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UsageCounter() {}

  public UsageCounter(UUID tenantId, LocalDate day, UsageMetric metric, long value) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.day = day;
    this.metric = metric.name();
    this.value = value;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public LocalDate getDay() {
    return day;
  }

  public String getMetric() {
    return metric;
  }

  public long getValue() {
    return value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
