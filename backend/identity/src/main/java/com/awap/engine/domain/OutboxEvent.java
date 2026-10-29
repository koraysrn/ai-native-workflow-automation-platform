package com.awap.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
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

  /** Lease coordinate: when the current relay claimed the row (null when unclaimed). */
  @Column(name = "claimed_at")
  private Instant claimedAt;

  /** Lease fencing token: a relay may only mark/release the row while it still holds this token. */
  @Column(name = "claim_token")
  private UUID claimToken;

  /** Set only when the event actually reached Kafka; legacy rows may be published without it. */
  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "publish_attempts", nullable = false)
  private int publishAttempts;

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
    this.publishAttempts = 0;
  }

  /**
   * Claims the row for a relay without publishing it. The claim is a lease, not a publication: a
   * crash while the lease is held leaves the row unpublished and recoverable by reconciliation.
   */
  public void claim(UUID token, Instant claimedAt) {
    if (published) {
      throw new IllegalStateException("Cannot claim an already published outbox event " + id);
    }
    this.claimToken = token;
    this.claimedAt = claimedAt;
  }

  /** Marks the row as actually published and clears the lease. */
  public void markPublished() {
    markPublished(Instant.now());
  }

  public void markPublished(Instant publishedAt) {
    this.published = true;
    this.publishedAt = publishedAt;
    this.claimedAt = null;
    this.claimToken = null;
  }

  /** Releases the lease after a failed publish and counts the attempt. */
  public void releaseClaim() {
    this.claimedAt = null;
    this.claimToken = null;
    this.publishAttempts = publishAttempts + 1;
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

  public Optional<Instant> getClaimedAt() {
    return Optional.ofNullable(claimedAt);
  }

  public Optional<UUID> getClaimToken() {
    return Optional.ofNullable(claimToken);
  }

  public Optional<Instant> getPublishedAt() {
    return Optional.ofNullable(publishedAt);
  }

  public int getPublishAttempts() {
    return publishAttempts;
  }
}
