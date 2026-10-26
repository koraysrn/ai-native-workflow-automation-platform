package com.awap.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Tenant- and day-scoped aggregation of process metrics written to PostgreSQL. */
@Entity
@Table(
    name = "analytics_aggregates",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_analytics_aggregates",
            columnNames = {"tenant_id", "day", "event_type"}))
public class AnalyticsAggregate {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "day", nullable = false)
  private LocalDate day;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "event_count", nullable = false)
  private long eventCount;

  @Column(name = "total_duration_ms", nullable = false)
  private long totalDurationMs;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AnalyticsAggregate() {}

  public AnalyticsAggregate(UUID tenantId, LocalDate day, String eventType) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.day = day;
    this.eventType = eventType;
    this.eventCount = 0;
    this.totalDurationMs = 0;
    this.updatedAt = Instant.now();
  }

  public void add(long durationMs) {
    this.eventCount++;
    this.totalDurationMs += Math.max(0, durationMs);
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

  public String getEventType() {
    return eventType;
  }

  public long getEventCount() {
    return eventCount;
  }

  public long getTotalDurationMs() {
    return totalDurationMs;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
