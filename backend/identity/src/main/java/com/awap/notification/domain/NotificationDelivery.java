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

/** Durable per-message delivery record used for de-duplication, audit and in-app notifications. */
@Entity
@Table(
    name = "notification_deliveries",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notification_deliveries",
            columnNames = {"tenant_id", "dedupe_key"}))
public class NotificationDelivery {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "dedupe_key", nullable = false)
  private String dedupeKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NotificationChannel channel;

  @Column(nullable = false)
  private String recipient;

  @Column(name = "template_key")
  private String templateKey;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationDelivery() {}

  public NotificationDelivery(
      UUID tenantId,
      String dedupeKey,
      NotificationChannel channel,
      String recipient,
      String templateKey) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.dedupeKey = dedupeKey;
    this.channel = channel;
    this.recipient = recipient;
    this.templateKey = templateKey;
    this.status = "PENDING";
    this.attempts = 0;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void recordAttempt() {
    this.attempts++;
    this.updatedAt = Instant.now();
  }

  public void markSent() {
    this.status = "SENT";
    this.lastError = null;
    this.updatedAt = Instant.now();
  }

  public void markFailed(String error) {
    this.status = "FAILED";
    this.lastError = error;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getDedupeKey() {
    return dedupeKey;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getTemplateKey() {
    return templateKey;
  }

  public String getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
