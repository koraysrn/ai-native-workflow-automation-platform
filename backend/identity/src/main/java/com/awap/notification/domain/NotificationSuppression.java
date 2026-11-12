package com.awap.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * A recipient that must never be contacted again on a channel (issue #204).
 *
 * <p>Hard bounces and spam complaints are permanent: retrying them only damages sender reputation.
 * The transport classifies the failure, the dispatcher records it here, and every later delivery to
 * the same {@code (tenant, channel, recipient)} is dropped before the provider call. The unique
 * constraint makes concurrent inserts of the same bounce idempotent.
 */
@Entity
@Table(
    name = "notification_suppressions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notification_suppressions",
            columnNames = {"tenant_id", "channel", "recipient"}))
public class NotificationSuppression {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private NotificationChannel channel;

  @Column(nullable = false, length = 255)
  private String recipient;

  @Column(nullable = false, length = 64)
  private String reason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected NotificationSuppression() {}

  public NotificationSuppression(
      UUID tenantId, NotificationChannel channel, String recipient, String reason) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.channel = channel;
    this.recipient = recipient;
    this.reason = reason;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getReason() {
    return reason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
