package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;

/** Durable domain event for reliable, outbox-style publication. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
  private String payloadJson;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private boolean published;

  @CreatedBy
  @Column(name = "created_by")
  private UUID createdBy;

  protected OutboxEvent() {}

  public OutboxEvent(
      UUID tenantId, String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payloadJson = payloadJson;
    this.createdAt = Instant.now();
    this.published = false;
  }

  public void markPublished() {
    this.published = true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayloadJson() {
    return payloadJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isPublished() {
    return published;
  }
}
